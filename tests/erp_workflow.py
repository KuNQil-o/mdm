"""Actual ERP → source-authenticated MDM → ERP acceptance, including uncertain-result recovery."""
import concurrent.futures, importlib, json, os, pathlib, sys, time, uuid
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[1]/'erp-v3'))
sys.path.insert(0,'/workspace/.tools/python')
from v3_support import Session,erp,ApiError,dumps
import psycopg
from psycopg.rows import dict_row
import workflow
os.environ.setdefault('ERP_DEMO_SOURCE_KEY','erp-glass-demo-dev-only')
CODE='ERP_GLASS_DEMO';tag='WF'+uuid.uuid4().hex[:10];out=[]
def connect():return psycopg.connect(host='localhost',port=5432,dbname='mdm_erp_v3',user='mdm',password='mdm_dev_only',row_factory=dict_row)
def fields(weight):return dict(weight_id=weight,manufacturer_id='M01',treatment_id='T01',width_id='W1270',form_id='F01',grade_id='G01',remarks=tag)
def new_weight(suffix):
 key=tag+suffix;erp('/records/cloth_weight','POST',{'id':key,'type_id':'TYPE01','cloth_code':key,'weight':210,'unit':'g/m2'});return key

def saved(key,data,assign=True):return erp('/erp/api/cloth','POST',{'integration':CODE,'id':key,'fields':data,'assign':assign})
def request(key):return erp('/erp/api/cloth/'+key+'/request-number','POST',{'integration':CODE})
def expect(code,fn):
 try:fn()
 except ApiError as e:
  assert e.data['code']==code,e.data;return e.data
 raise AssertionError('expected '+code)
def ledger(key):return [r for r in Session().call('/assignment-ledger') if r['sourceRecordKey']==key and r['datasetCode']==CODE]
def check(name,fn):
 result=fn();out.append({'name':name,'passed':True,'detail':result});print('PASS',name,flush=True)

config=erp('/erp/api/config');assert config['integrations'] and 'key_env' not in dumps(config) and os.environ['ERP_DEMO_SOURCE_KEY'] not in dumps(config)
key=tag+'-main';data=fields(new_weight('A'))
def save_only():
 r=saved(key,data,False);assert r['state']=='SAVED' and r['record']['material_no'] is None and not ledger(key);return r['record']['id']
check('ERP 仅保存持久化、不占号',save_only)
def success():
 r=request(key);assert r['state']=='CONFIRMED';assert r['record']['material_no'].startswith('GC') and len(r['record']['material_no'])==8
 assert erp('/source/cloth/'+key)['material_no']==r['assignment']['materialNo'];assert len(ledger(key))==1
 a=ledger(key)[0];assert a['inputSnapshot']['attributes']['basisWeight']['value']==210 and a['inputSnapshot']['attributes']['width']['value']==1270
 return r['record']['material_no']
check('无需 MDM 登录，七关联取数、标准化、原子发号并回填 ERP',success)
original=erp('/source/cloth/'+key)['material_no']
def duplicate_request():
 with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:results=list(pool.map(lambda _:request(key),range(12)))
 assert all(r['record']['material_no']==original for r in results);assert len(ledger(key))==1;return {'requests':12,'assignments':1,'materialNo':original}
check('同一记录并发重复申请仅一个正式号',duplicate_request)
def duplicate_identity():
 err=expect('IDENTITY_CONFLICT',lambda:saved(tag+'-duplicate',data));assert err['recordSaved'];assert erp('/source/cloth/'+tag+'-duplicate')['material_no'] is None
 assert not ledger(tag+'-duplicate');return err['code']
check('不同 ERP 记录相同 Identity 拒绝且保留来源记录',duplicate_identity)
def units():
 normalized={**data,'width_id':'W127CM'};return expect('IDENTITY_CONFLICT',lambda:saved(tag+'-units',normalized))['code']
check('127 cm 与 1270 mm 标准化后身份相同',units)
def invalid_then_fix():
 k=tag+'-invalid';bad={**fields(new_weight('B')),'weight_id':'DOES_NOT_EXIST'}
 err=expect('SOURCE_INCOMPLETE',lambda:saved(k,bad));assert err['recordSaved'] and not ledger(k)
 r=saved(k,fields(tag+'B'));assert r['state']=='CONFIRMED' and len(ledger(k))==1;return r['record']['material_no']
check('来源关联缺失：失败不占号，修正同一 ERP 记录可恢复',invalid_then_fix)
check('已发号规格修改被拒绝',lambda:expect('ISSUED_RECORD_LOCKED',lambda:saved(key,{**data,'width_id':'W1280'}))['code'])
def remarks():
 r=saved(key,{**data,'remarks':'更新备注'});assert r['record']['material_no']==original;return r['record']['remarks']
check('非身份备注修改保留原号',remarks)
def erp_conflict():
 erp('/records/cloth/'+key,'PATCH',{'material_no':'EXTERNAL-CONFLICT'})
 try:return expect('ERP_VALUE_CONFLICT',lambda:request(key))['code']
 finally:erp('/records/cloth/'+key,'PATCH',{'material_no':original})
check('ERP 非空不同号不被覆盖',erp_conflict)
def reference_changed():
 erp('/records/cloth_weight/'+data['weight_id'],'PATCH',{'weight':211})
 try:return expect('IDENTITY_CHANGED_AFTER_ISSUE',lambda:request(key))['code']
 finally:erp('/records/cloth_weight/'+data['weight_id'],'PATCH',{'weight':210})
check('参考表改变身份后重申报不重编号',reference_changed)
def lost_response():
 k=tag+'-lost';saved(k,fields(new_weight('C')),False)
 actual=workflow.call_mdm
 def lost(cfg,key,idem):
  actual(cfg,key,idem)
  raise workflow.WorkflowError(503,'MDM_RESULT_UNKNOWN','模拟 MDM 已发号但 ERP 未收到响应')
 workflow.call_mdm=lost
 try:
  try:workflow.perform(connect,dumps,CODE,k)
  except workflow.WorkflowError as e:assert e.body['state']=='RESULT_UNKNOWN'
  else:raise AssertionError('lost response did not fail')
 finally:workflow.call_mdm=actual
 with connect() as c:prior=c.execute('select * from erp_number_request where source_record_key=%s',(k,)).fetchone()
 assert len(ledger(k))==1
 importlib.reload(workflow)  # Only persisted state survives module recreation.
 r=request(k)
 with connect() as c:after=c.execute('select * from erp_number_request where source_record_key=%s',(k,)).fetchone()
 assert after['idempotency_key']==prior['idempotency_key'];assert r['state']=='CONFIRMED' and len(ledger(k))==1
 return r['record']['material_no']
check('MDM 已发号但响应丢失，恢复后沿用持久幂等键和原号',lost_response)
def confirmation():
 end=time.monotonic()+15
 while time.monotonic()<end:
  explanation=Session().call('/assignment-ledger/'+ledger(key)[0]['id']+'/explain')
  if explanation['writebackTasks'][-1]['state']=='SUCCEEDED':return 'SUCCEEDED'
  time.sleep(.3)
 raise AssertionError('MDM writeback did not query-confirm ERP saved value')
# A prior external conflict may have made the task FAILED. Use a fresh confirmed row for this check.
def fresh_confirmation():
 global key
 key=tag+'-confirm';saved(key,fields(new_weight('D')));return confirmation()
check('ERP 自保存后的 MDM 独立回写任务查询确认',fresh_confirmation)
pathlib.Path('.runtime/erp-workflow-results.json').write_text(dumps({'runId':tag,'results':out}),encoding='utf8')
print(json.dumps({'passed':len(out),'runId':tag},ensure_ascii=False))
