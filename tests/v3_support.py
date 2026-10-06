"""V3 acceptance fixtures. All category variation below is test metadata, never application branches."""
import json,uuid,decimal,time,re,urllib.request,urllib.error,http.cookiejar,copy,pathlib
BASE='http://127.0.0.1:8080/api/v1';ERP='http://127.0.0.1:9092'
def dumps(value):
 raw=json.dumps(value,ensure_ascii=False,default=lambda v:'__DEC__'+str(v)+'__END__' if isinstance(v,decimal.Decimal) else str(v))
 return re.sub(r'"__DEC__(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)__END__"',r'\1',raw)
class ApiError(Exception):
 def __init__(self,status,data):self.status=status;self.data=data;super().__init__(str(status)+' '+dumps(data))
class Session:
 def __init__(self,user='editor',tenant='TENANT-A'):
  self.tenant=tenant;self.cookies=http.cookiejar.CookieJar();self.opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookies));self.call('/api/dev/login','POST',{'userCode':user})
 def call(self,path,method='GET',body=None,version=None,idem=None,headers=None):
  url=path if path.startswith('http') else 'http://127.0.0.1:8080'+path if path.startswith('/api/') else BASE+path
  h={'X-Tenant-Code':self.tenant,'X-Request-ID':str(uuid.uuid4())}
  if method!='GET':h['Idempotency-Key']=idem or str(uuid.uuid4())
  if body is not None:h['Content-Type']='application/json'
  if version is not None:h['If-Match']=str(version)
  h.update(headers or {});req=urllib.request.Request(url,None if body is None else dumps(body).encode(),h,method=method)
  try:
   with self.opener.open(req,timeout=20) as r:return json.loads(r.read(),parse_float=decimal.Decimal)
  except urllib.error.HTTPError as e:raise ApiError(e.code,json.loads(e.read()))
def erp(path,method='GET',body=None):
 req=urllib.request.Request(ERP+path,None if body is None else dumps(body).encode(),{'Content-Type':'application/json'},method=method)
 try:
  with urllib.request.urlopen(req,timeout=15) as r:return json.loads(r.read(),parse_float=decimal.Decimal)
 except urllib.error.HTTPError as e:raise ApiError(e.code,json.loads(e.read()))
def field(name,kind='STRING',**kw):return {'code':name,'type':kind,**kw}
def enum(name,values,**kw):return field(name,'ENUM',options=[{'code':v,'active':True} for v in values],**kw)
def ast(op,*args):return {'op':op,'args':list(args)}
def prop(name):return {'field':name}
def decimal_field(name,unit='mm',**kw):return field(name,'DECIMAL',unit=unit,scale=3,precision=12,min=0,**kw)
class Fixture:
 def __init__(self,editor,reviewer,tag,kind,attrs,identity,segments,base,validations=None,parse_rule=None,sequence=False,expected=None):
  self.e=editor;self.r=reviewer;self.tag=tag;self.kind=kind;self.code=tag+'_'+kind;self.base=base;self.counter=0
  assert expected is not None, "Golden expectation must be supplied independently"
  self.expected=self.code+'-'+expected
  self.category=editor.call('/categories','POST',{'code':self.code,'name':'验收 '+kind})
  self.client_key='demo-'+uuid.uuid4().hex
  self.source=editor.call('/source-systems','POST',{'code':self.code,'type':'REST','clientKey':self.client_key,'connectionProfile':{'baseUrl':ERP,'allowManualAssign':True}})
  self.source=editor.call('/source-systems/'+self.source['id'],'PATCH',{'status':'ACTIVE'},self.source['rowVersion'])
  normal=[f for f in attrs if 'derived' not in f];raw_names=[f['code'] for f in normal]+['id','category','materialNo','sourceVersion','sourceUpdatedAt']
  self.object=editor.call('/source-objects','POST',{'sourceSystemId':self.source['id'],'definition':{'objectName':self.code,'objectType':'API','fields':[{'name':n,'dataType':'VARCHAR' if n!='sourceVersion' else 'INTEGER'} for n in raw_names],'primaryKey':['id']}})
  self.dataset=editor.call('/source-datasets','POST',{'code':self.code,'sourceSystemId':self.source['id'],'categoryCode':self.code,'definition':{'root':{'objectVersionId':self.object['id'],'alias':'g'},'sourceKey':['g.id'],'grain':'ONE_ROOT_ROW_ONE_MATERIAL','resource':'/documents/{key}','scanResource':'/documents','candidateFilter':ast('eq',prop('g.category'),self.code),'output':{f['code']:'g.'+f['code'] for f in normal},'existingNoField':'g.materialNo','sourceVersionField':'g.sourceVersion','incremental':{'strategy':'VERSION_COLUMN','referenceStrategy':'RESCAN_UNISSUED'}}})
  self.code_definition={'separator':'-','segments':[{'type':'CONST','value':self.code}]+segments+([{'type':'SEQUENCE','name':'MAIN','width':6}] if sequence else [])}
  if parse_rule:
   parse_rule=copy.deepcopy(parse_rule)
   if sequence and not parse_rule['pattern'].endswith('[0-9]{6}'):parse_rule['pattern']+='-[0-9]{6}'
   self.code_definition['parseRule']=parse_rule
  self.bundle={'attributes':attrs,'validations':validations or [],'codeRule':self.code_definition}
  self.schema=editor.call('/categories/'+self.code+'/schemas','POST',{'bundle':self.bundle})
  self.mapping=editor.call('/field-mappings','POST',{'code':self.code,'categoryCode':self.code,'definition':{'fields':[{'target':f['code'],'source':f['code']} for f in normal]}})
  self.identity=editor.call('/identity-definitions','POST',{'code':self.code,'categoryCode':self.code,'definition':{'fields':[{'field':f,'allowNull':not next(a for a in attrs if a['code']==f).get('required',False)} for f in identity]}})
  self.rule=editor.call('/code-rules','POST',{'code':self.code,'categoryCode':self.code,'definition':self.code_definition})
  sample=self.record(base,'GOLDEN');self.release=self.create_release(sample)
 def record(self,data=None,suffix=None):
  self.counter+=1;key=self.code+'-'+(suffix or str(self.counter)+'-'+uuid.uuid4().hex[:6]);erp('/documents','POST',{'id':key,'category':self.code,'data':copy.deepcopy(self.base if data is None else data)});return key
 def create_release(self,sample,schema=None,rule=None,dataset=None,mapping=None,identity=None):
  schema=schema or self.schema;rule=rule or self.rule;dataset=dataset or self.dataset;mapping=mapping or self.mapping;identity=identity or self.identity
  b={'code':self.code,'categoryCode':self.code,'datasetVersionId':dataset['id'],'schemaVersionId':schema['id'],'mappingVersionId':mapping['id'],'identityDefinitionVersionId':identity['id'],'codeRuleVersionId':rule['id'],'definition':{'samples':[{'sourceRecordKey':sample,'expectedMaterialNo':self.expected}],'writeback':{'baseUrl':ERP,'path':'/writeback/flat_material/{key}','queryPath':'/writeback/flat_material/{key}','contractPath':'/contract','businessValidationPath':'/contract/validate','conditionalEmptyWrite':True,'supportsIdempotency':True}}}
  r=self.e.call('/releases','POST',b);r=self.e.call('/releases/'+r['id']+'/submit','POST',{},r['rowVersion']);return self.r.call('/releases/'+r['id']+'/approve','POST',{},r['rowVersion'])
 def preview(self,key,release=None):return self.e.call('/material-numbers:preview','POST',{'releaseId':(release or self.release)['id'],'sourceRecordKey':key})
 def assign(self,key,release=None,idem=None,source=False):return self.e.call('/material-numbers:assign','POST',{'releaseId':(release or self.release)['id'],'sourceRecordKey':key},idem=idem,headers={'X-Source-Key':self.client_key} if source else None)
 def explain(self,result):return self.e.call('/assignment-ledger/'+result['assignmentId']+'/explain')
 def write(self,result):
  tasks=self.explain(result)['writebackTasks'];return self.e.call('/writeback-tasks/'+tasks[-1]['id']+'/process','POST',{})
 def parse(self,no,rule=None):return self.e.call('/material-numbers:parse','POST',{'codeRuleVersionId':(rule or self.rule)['id'],'materialNo':no})
def segments(names):return [{'type':'ATTR','field':n} for n in names]
def parse_rule(code,names,patterns=None,units=None):
 patterns=patterns or {};units=units or {};parts=[];fields={}
 for i,n in enumerate(names):
  group='f'+str(i);parts.append('(?<'+group+'>'+patterns.get(n,'[A-Za-z0-9_.]+')+')');fields[n]={'group':group,**({'unit':units[n]} if n in units else {})}
 return {'kind':'REGEX','pattern':re.escape(code)+'-'+'-'.join(parts),'fields':fields}
class GlassFixture(Fixture):
 def __init__(self,e,r,tag):
  names=['type','clothCode','manufacturer','treatment','form','width','grade']
  attrs=[field(n,required=True,searchable=True) for n in names if n!='width']+[decimal_field('width',required=True,searchable=True),field('remarks')]
  base={'type':'E','clothCode':'7628','manufacturer':'HH','treatment':'A','form':'R','width':{'value':1270,'unit':'mm'},'grade':'A'}
  super().__init__(e,r,tag,'GC',attrs,names,segments(names),base,parse_rule=parse_rule(tag+'_GC',names,units={'width':'mm'}),expected='E-7628-HH-A-R-1270-A')
  self.client_key='demo-'+uuid.uuid4().hex
  self.source=e.call('/source-systems','POST',{'code':self.code+'_DB','clientKey':self.client_key,'connectionProfile':{'jdbcUrl':'jdbc:postgresql://localhost:5432/mdm_erp_v3','userEnv':'ERP_READ_USER','passwordEnv':'ERP_READ_PASSWORD','writebackBaseUrl':ERP,'allowManualAssign':True}})
  self.source=e.call('/source-systems/'+self.source['id'],'PATCH',{'status':'ACTIVE'},self.source['rowVersion']);self.objects={o['objectName']:o for o in e.call('/source-systems/'+self.source['id']+'/discover','POST',{'objects':[{'objectName':n} for n in ['cloth','manufacturer','treatment','cloth_weight','cloth_type','form_ref','width_ref','grade_ref','duplicate_ref']]})}
  joins=[]
  for table,alias,left in [('manufacturer','m','g.manufacturer_id'),('cloth_weight','bw','g.weight_id'),('cloth_type','t','bw.type_id'),('treatment','tr','g.treatment_id'),('form_ref','f','g.form_id'),('width_ref','w','g.width_id'),('grade_ref','gr','g.grade_id')]:joins.append({'objectVersionId':self.objects[table]['id'],'alias':alias,'type':'LEFT','cardinality':'N:1','nullPolicy':'ERROR','activeField':'active','activeValue':True,'on':[{'left':left,'right':alias+'.id'}]})
  definition={'root':{'objectVersionId':self.objects['cloth']['id'],'alias':'g'},'sourceKey':['g.id'],'grain':'ONE_ROOT_ROW_ONE_MATERIAL','joins':joins,'output':{'type':'t.code','clothCode':'bw.cloth_code','manufacturer':'m.code','treatment':'tr.code','form':'f.code','width':'w.width','widthUnit':'w.unit','grade':'gr.code','remarks':'g.remarks'},'existingNoField':'g.material_no','sourceVersionField':'g.version','candidateFilter':ast('eq',prop('g.status'),self.code),'incremental':{'strategy':'VERSION_COLUMN','referenceStrategy':'RESCAN_UNISSUED'}}
  self.dataset=e.call('/source-datasets','POST',{'code':self.code,'sourceSystemId':self.source['id'],'categoryCode':self.code,'definition':definition})
  mapped=copy.deepcopy(self.mapping['definition']);next(f for f in mapped['fields'] if f['target']=='width')['unitSource']='widthUnit';self.mapping=e.call('/field-mappings','POST',{'code':self.code,'categoryCode':self.code,'definition':mapped})
  self.expected=self.code+'-E-7628-HH-A-R-1270-A';sample=self.record({},'DB_GOLDEN');old_create=self.create_release
  # Same independently specified Golden rule, now sourcing actual multi-table ERP rows.
  self.release=self.create_glass_release(sample)
 def record(self,data=None,suffix=None):
  if not hasattr(self,'objects'):return super().record(data,suffix)
  self.counter+=1;key=self.code+'-'+(suffix or str(self.counter)+'-'+uuid.uuid4().hex[:6]);b={'id':key,'weight_id':'BW210','manufacturer_id':'M01','treatment_id':'T01','form_id':'F01','width_id':'W1270','grade_id':'G01','status':self.code};b.update(data or {});erp('/records/cloth','POST',b);return key
 def create_glass_release(self,sample,rule=None,schema=None):
  rule=rule or self.rule;schema=schema or self.schema
  b={'code':self.code,'categoryCode':self.code,'datasetVersionId':self.dataset['id'],'schemaVersionId':schema['id'],'mappingVersionId':self.mapping['id'],'identityDefinitionVersionId':self.identity['id'],'codeRuleVersionId':rule['id'],'definition':{'samples':[{'sourceRecordKey':sample,'expectedMaterialNo':self.expected}],'writeback':{'baseUrl':ERP,'path':'/writeback/cloth/{key}','queryPath':'/writeback/cloth/{key}','contractPath':'/contract','businessValidationPath':'/contract/validate','conditionalEmptyWrite':True,'supportsIdempotency':True}}}
  release=self.e.call('/releases','POST',b);release=self.e.call('/releases/'+release['id']+'/submit','POST',{},release['rowVersion']);return self.r.call('/releases/'+release['id']+'/approve','POST',{},release['rowVersion'])
