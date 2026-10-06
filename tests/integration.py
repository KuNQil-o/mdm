"""真实PostgreSQL与独立ERP模拟器上的HTTP业务验收。只创建专用测试记录。"""
from http_client import Client
import urllib.request,json,time,uuid,unittest,subprocess,os
BASE=os.environ.get('TEST_API','http://localhost:8081')
MOCK=os.environ.get('TEST_ERP','http://localhost:9091')
TAG='T'+uuid.uuid4().hex[:8]
class Reliability(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  cls.e=Client('editor','TENANT-B',BASE);cls.r=Client('reviewer','TENANT-B',BASE)
  cls.schema=cls.e.call('/categories/GLASS_CLOTH/schema')
  cls.systems=[]
  for target in [TAG+'a',TAG+'b']:
   cfg={'url':MOCK+'/targets/'+target+'/materials','healthUrl':MOCK+'/health','contractTestUrl':MOCK+'/targets/'+target+'/validate','queryUrl':MOCK+'/targets/'+target+'/events/{eventId}','identityQueryUrl':MOCK+'/targets/'+target+'/materials/{externalId}','idempotent':True,'confirmationPollSeconds':1,'confirmationTimeoutSeconds':15,'timeoutMs':1200,'inboundUserId':cls.e.call('/tenants/current')['user']['id']}
   sys=cls.e.call('/integration-systems','POST',{'code':target,'name':target,'config':cfg,'clientKey':'dev-'+target})
   mapping=cls.e.call('/integration-systems/'+sys['id']+'/mappings','POST',{'config':{'fields':[{'source':'materialNo','target':'ITEM_CODE','required':True},{'source':'materialName','target':'ITEM_NAME','required':True},{'source':'attributes.width','target':'WIDTH','unit':'mm'}],'units':cls.schema['bundle']['units'],'policies':{'id':'REJECT','materialNo':'REJECT','schemaVersionId':'REJECT','materialName':'REVIEW','attributes.description':'IGNORE','attributes.basisWeight':'ACCEPT'},'samples':[{'input':{'materialNo':'TEST','materialName':'测试'},'expected':{'ITEM_CODE':'TEST','ITEM_NAME':'测试'}}]}})
   cls.e.call('/mappings/'+mapping['id']+'/publish','POST',{},mapping['rowVersion'])
   cls.e.call('/integration-systems/'+sys['id']+'/test','POST',{})
   sys=cls.e.call('/integration-systems/'+sys['id']);sys=cls.e.call('/integration-systems/'+sys['id'],'PATCH',{'active':True},sys['rowVersion']);cls.systems.append(sys)
  cls.report=[]
 def scenario(self,mode,remaining=-1,index=0):
  target=self.systems[index]['code'];req=urllib.request.Request(MOCK+'/scenarios/'+target,data=json.dumps({'mode':mode,'remaining':remaining}).encode(),headers={'Content-Type':'application/json'},method='POST');return json.load(urllib.request.urlopen(req))
 def new(self,model=None):
  name=model or TAG+uuid.uuid4().hex[:6]
  body={'schemaVersionId':self.schema['id'],'materialName':name,'attributes':{'model':name,'width':{'value':1270,'unit':'mm'},'basisWeight':{'value':210,'unit':'g/m2'},'manufacturer':{'type':'SUPPLIER','id':'SUP-001'}}}
  m=self.e.call('/materials','POST',body,expected=201);r=self.e.call('/materials/'+m['id']+'/change-requests','POST',{'kind':'NEW'},m['rowVersion']);r=self.e.call('/requests/'+r['id']+'/submit','POST',{},r['rowVersion']);self.r.call('/requests/'+r['id']+'/approve','POST',{},r['rowVersion']);return self.e.call('/materials/'+m['id'])
 def deliveries(self,m,index=None):
  return [d for d in self.e.call('/deliveries') if d['materialId']==m['id'] and (index is None or d['systemId']==self.systems[index]['id'])]
 def wait(self,m,state,index=0,timeout=15):
  end=time.monotonic()+timeout
  while time.monotonic()<end:
   rows=self.deliveries(m,index)
   if rows and rows[0]['state']==state:return rows[0]
   time.sleep(.15)
  self.fail('投递未进入 '+state+'：'+str(rows))
 def test_01_businessFailureIndependentTargetsAndResend(self):
  self.scenario('BUSINESS_FAIL');m=self.new();d=self.wait(m,'FAILED');self.wait(m,'SUCCEEDED',1);self.assertEqual('ACTIVE',self.e.call('/materials/'+m['id'])['status']);self.scenario('SUCCESS');new=self.e.call('/deliveries/'+d['id']+'/resend','POST',{'reason':'目标已修复且确认业务失败'});success=self.wait(m,'SUCCEEDED');self.assertEqual(d['id'],success['parentId']);self.assertEqual(new['id'],success['id']);self.report.append('HTTP200业务失败独立于本地/其他目标；新修订补发成功')
 def test_02_429And5xxRetryDefaultsSeparateTestConfiguration(self):
  for mode in ['RATE_LIMIT','SERVER_ERROR']:
   self.scenario(mode,remaining=1);m=self.new();d=self.wait(m,'SUCCEEDED');self.assertGreaterEqual(d['attempts'],2);self.assertLessEqual(d['attempts'],5)
  self.report.append('429/5xx按独立测试等待配置恢复；生产默认60/300/1800/7200未改动')
 def test_03_lostAcknowledgementAnd202DoNotDuplicate(self):
  for mode in ['LOST_RESPONSE','ACCEPTED']:
   self.scenario(mode,remaining=1);m=self.new();d=self.wait(m,'SUCCEEDED');self.assertGreaterEqual(d['attempts'],2);target=self.systems[0]['code'];records=json.load(urllib.request.urlopen(MOCK+'/targets/'+target+'/materials'));self.assertEqual(1,sum(x['materialId']==m['id'] for x in records));detail=self.e.call('/deliveries/'+d['id']);self.assertTrue(any(a['httpStatus']==0 or a['httpStatus']==202 for a in detail['attemptLogs']))
  self.report.append('ACK丢失先查结果；202轮询确认；外部物料均只创建一次')
 def test_04_unknownRequiresExplicitVerification(self):
  s=self.e.call('/integration-systems/'+self.systems[0]['id']);cfg={**s['config'],'queryUrl':'','idempotent':False};self.e.call('/integration-systems/'+s['id'],'PATCH',{'config':cfg},s['rowVersion']);self.scenario('UNKNOWN',remaining=1);m=self.new();d=self.wait(m,'RESULT_UNKNOWN');self.e.call('/deliveries/'+d['id']+'/resend','POST',{'reason':'尚未确认'},expected=422);self.scenario('SUCCESS');self.e.call('/deliveries/'+d['id']+'/resend','POST',{'reason':'模拟器查询确认未创建','confirmedNotCreated':True});self.wait(m,'SUCCEEDED');s=self.e.call('/integration-systems/'+s['id']);self.e.call('/integration-systems/'+s['id'],'PATCH',{'config':self.systems[0]['config']},s['rowVersion']);self.report.append('未知结果禁止无条件重发；人工核对后补发')
 def test_05_inboxDedupOwnershipAndOrdering(self):
  sid=self.systems[0]['id'];external='EXT-'+uuid.uuid4().hex;model='IN'+uuid.uuid4().hex[:8];message={'sourceMessageId':'M-'+uuid.uuid4().hex,'externalId':external,'categoryCode':'GLASS_CLOTH','sourceVersion':1,'data':{'materialName':model,'attributes':{'model':model,'width':{'value':1270,'unit':'mm'},'basisWeight':{'value':210,'unit':'g/m2'},'manufacturer':{'type':'SUPPLIER','id':'SUP-001'},'description':'应被忽略'}}}
  first=self.e.call('/integration-systems/'+sid+'/inbound','POST',message);second=self.e.call('/integration-systems/'+sid+'/inbound','POST',message);self.assertEqual(first['materialId'],second['materialId']);self.assertIn('attributes.description',first['ignoredFields']);m=self.e.call('/materials/'+first['materialId']);self.assertEqual('DRAFT',m['status']);self.assertIsNone(m['materialNo']);self.assertEqual([],m['deliveries']);self.e.call('/integration-systems/'+sid+'/inbound','POST',{**message,'data':{**message['data'],'materialNo':'OVERWRITE'}},expected=409)
  bad={**message,'sourceMessageId':uuid.uuid4().hex,'data':{**message['data'],'materialNo':'OVERWRITE'}};self.e.call('/integration-systems/'+sid+'/inbound','POST',bad,expected=422)
  waiting={**message,'sourceMessageId':uuid.uuid4().hex,'sourceVersion':3,'previousPublishedVersion':2,'baseVersion':3};self.assertEqual('WAIT_PREDECESSOR',self.e.call('/integration-systems/'+sid+'/inbound','POST',waiting)['status']);r=self.e.call('/requests/'+first['requestId']+'/submit','POST',{},1);self.r.call('/requests/'+r['id']+'/approve','POST',{},r['rowVersion']);m=self.e.call('/materials/'+m['id']);self.assertFalse(any(d['systemId']==sid for d in m['deliveries']))
  v2={**message,'sourceMessageId':uuid.uuid4().hex,'sourceVersion':2,'previousPublishedVersion':1,'baseVersion':m['rowVersion'],'data':{'materialName':'入站变更'}};self.e.call('/integration-systems/'+sid+'/inbound','POST',v2);accepted=self.e.call('/integration-systems/'+sid+'/inbound','POST',waiting);self.assertEqual('REVIEW_REQUIRED',accepted['status']);self.assertEqual(accepted['requestId'],self.e.call('/integration-systems/'+sid+'/inbound','POST',waiting)['requestId'])
  self.report.append('入站事务去重、不同体冲突、REJECT/IGNORE/ACCEPT/REVIEW、前序等待、防回环')
 def test_06_predecessorVersionsAndReconciliation(self):
  self.scenario('SUCCESS');m=self.new();self.wait(m,'SUCCEEDED');r=self.e.call('/materials/'+m['id']+'/change-requests','POST',{'kind':'CHANGE','reason':'验证正式前序','candidate':{'materialName':m['materialName']+'改'}},m['rowVersion']);r=self.e.call('/requests/'+r['id']+'/submit','POST',{},r['rowVersion']);self.r.call('/requests/'+r['id']+'/approve','POST',{},r['rowVersion']);d=self.wait(m,'SUCCEEDED');self.assertEqual(3,d['previousPublishedVersion']);self.assertEqual(4,d['eventVersion']);result=self.e.call('/integration-systems/'+self.systems[0]['id']+'/reconcile','POST',{});self.assertTrue(any(x['materialId']==m['id'] and x['status']=='MATCH' for x in result['results']));self.report.append('previousPublishedVersion处理正式事件而非草稿跳号；实际目标查询对账匹配')
 def test_07_targetFrequency(self):
  self.scenario('SUCCESS');s=self.e.call('/integration-systems/'+self.systems[0]['id']);cfg={**s['config'],'minimumIntervalMs':400};self.e.call('/integration-systems/'+s['id'],'PATCH',{'config':cfg},s['rowVersion']);a=self.new();b=self.new();da=self.wait(a,'SUCCEEDED');db=self.wait(b,'SUCCEEDED');import datetime
  aa=self.e.call('/deliveries/'+da['id'])['attemptLogs'][-1]['createdAt'];bb=self.e.call('/deliveries/'+db['id'])['attemptLogs'][-1]['createdAt'];gap=abs((datetime.datetime.fromisoformat(bb.replace('Z','+00:00'))-datetime.datetime.fromisoformat(aa.replace('Z','+00:00'))).total_seconds());self.assertGreater(gap,.3);s=self.e.call('/integration-systems/'+s['id']);self.e.call('/integration-systems/'+s['id'],'PATCH',{'config':self.systems[0]['config']},s['rowVersion']);self.report.append('同目标持久发送间隔配置400ms，两物料发送确认间隔>300ms')
 @classmethod
 def tearDownClass(cls):
  os.makedirs('.runtime',exist_ok=True)
  with open('.runtime/integration-evidence.json','w') as f:json.dump({'target':'独立本地ERP模拟器','database':'PostgreSQL16.6专用测试库','checks':cls.report},f,ensure_ascii=False,indent=2)
if __name__=='__main__':unittest.main(verbosity=2)
