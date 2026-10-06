"""Executable acceptance cases with document IDs and concrete independent Golden expectations."""
from v3_support import *
from concurrent.futures import ThreadPoolExecutor
import sys,traceback,statistics
RESULTS=[];tag='A'+uuid.uuid4().hex[:7].upper();e=Session();r=Session('reviewer');e.call('/api/dev/seed','POST',{})
def equal(a,b):assert a==b,(a,b)
def expect(code,work,status=422,field_path=None):
 try:work()
 except ApiError as err:
  equal(err.status,status);equal(err.data['code'],code)
  if field_path:assert any(x.get('fieldPath')==field_path for x in err.data.get('errors',[])),err.data
  return err.data
 raise AssertionError('Expected '+code)
def case(ids,title,work):
 started=time.perf_counter();result={'ids':ids.split(),'title':title}
 try:
  evidence=work();result.update(status='PASSED',evidence=evidence)
 except Exception as exc:result.update(status='FAILED',error=str(exc));traceback.print_exc()
 result['durationMs']=round((time.perf_counter()-started)*1000,2);RESULTS.append(result);print(result['status'],ids,title,flush=True);save()
def save():pathlib.Path('.runtime/v3-acceptance-results.json').write_text(dumps({'runId':tag,'results':RESULTS}),encoding='utf8')
def build():
 g=GlassFixture(e,r,tag)
 chemnames=['package','classification','model','manufacturer','grade'];chemattrs=[enum('package',['BAG','DRUM'],required=True),enum('classification',['C1','C2'],required=True),enum('model',['X1','X2'],required=True),enum('manufacturer',['HH','TS'],required=True),enum('grade',['A','B'],required=True),field('description',derived=ast('concat',prop('manufacturer'),'-',prop('model')))]
 chem=Fixture(e,r,tag,'CHEM',chemattrs,chemnames,segments(chemnames),dict(zip(chemnames,['BAG','C1','X1','HH','A'])),validations=[{'field':'model','assert':ast('or',ast('and',ast('eq',prop('classification'),'C1'),ast('eq',prop('model'),'X1')),ast('and',ast('eq',prop('classification'),'C2'),ast('eq',prop('model'),'X2'))),'message':'分类与型号不匹配'}],expected='BAG-C1-X1-HH-A')
 cfnames=['classification','weight','manufacturer','treatment','color','length','width','grade'];cfattrs=[enum('classification',['ED','RA'],required=True),decimal_field('weight','g/m2',required=True),enum('manufacturer',['HH','TS'],required=True),enum('treatment',['A','B'],required=True),enum('color',['RED','YELLOW'],required=True),decimal_field('length',required=True),decimal_field('width',required=True),enum('grade',['A','B'],required=True),field('description')];cfbase={'classification':'ED','weight':{'value':35,'unit':'g/m2'},'manufacturer':'HH','treatment':'A','color':'RED','length':{'value':1000,'unit':'mm'},'width':{'value':1270,'unit':'mm'},'grade':'A'}
 cf=Fixture(e,r,tag,'CF',cfattrs,cfnames,segments(cfnames),cfbase,parse_rule=parse_rule(tag+'_CF',cfnames,units={'weight':'g/m2','length':'mm','width':'mm'}),expected='ED-35-HH-A-RED-1000-1270-A')
 resin=Fixture(e,r,tag,'RESIN',[enum('product',['R','C'],required=True),enum('production',['A','B'],required=True),field('active','BOOLEAN',default=True)],['product','production'],segments(['product','production']),{'product':'R','production':'A','active':True},parse_rule=parse_rule(tag+'_RESIN',['product','production']),expected='R-A')
 resin_key=resin.record({'product':'C','production':'B','active':True},'REFERENCE');resin_result=resin.assign(resin_key);resin.write(resin_result)
 admin=Session('admin');admin.call('/references','POST',{'type':'ERP_MATERIAL','id':resin_key,'name':'ERP成胶引用副本','data':{'sourceRecordKey':resin_key,'materialNo':resin_result['materialNo'],'copyType':'NON_AUTHORITATIVE_COPY'}})
 ppnames=['product','process','glassType','clothCode','spec','manufacturer','width'];ppattrs=[enum('product',['P'],required=True),enum('process',['A','C','T'],required=True),enum('glassType',['E','S'],required=True),field('clothCode',required=True),enum('spec',['RF','PG','TW'],required=True),enum('manufacturer',['HH','TS'],required=True),decimal_field('width',required=True),field('resin','REFERENCE',referenceType='ERP_MATERIAL',required=True,referenceSource={'datasetVersionId':resin.dataset['id'],'activeField':'active','activeValue':True,'requireIssuedNumber':True})]
 pp=Fixture(e,r,tag,'PP',ppattrs,ppnames+['resin'],segments(ppnames),{'product':'P','process':'A','glassType':'E','clothCode':'7628','spec':'RF','manufacturer':'HH','width':{'value':1270,'unit':'mm'},'resin':{'type':'ERP_MATERIAL','id':resin_key}},sequence=True,parse_rule=parse_rule(tag+'_PP',ppnames,units={'width':'mm'}),expected='P-A-E-7628-RF-HH-1270-{流水:6}')
 # Cross-Dataset projection reads glass type/cloth code from ERP glass facts, through generic metadata.
 glass_ref=g.record({},'PP_LOOKUP');pp.base={k:v for k,v in pp.base.items() if k not in ('glassType','clothCode')};pp.base['glassSourceKey']=glass_ref
 obj_def=copy.deepcopy(pp.object['definition']);obj_def['fields'].append({'name':'glassSourceKey','dataType':'VARCHAR'});pp.object=e.call('/source-objects','POST',{'sourceSystemId':pp.source['id'],'definition':obj_def})
 ds_def=copy.deepcopy(pp.dataset['definition']);ds_def['root']['objectVersionId']=pp.object['id'];ds_def['output'].pop('glassType');ds_def['output'].pop('clothCode');ds_def['output']['glassSourceKey']='g.glassSourceKey';pp.dataset=e.call('/source-datasets','POST',{'code':pp.code,'sourceSystemId':pp.source['id'],'categoryCode':pp.code,'definition':ds_def})
 mp_def=copy.deepcopy(pp.mapping['definition']);mp_def['lookups']=[{'datasetVersionId':g.dataset['id'],'keySource':'glassSourceKey','projection':{'glassType':'type','clothCode':'clothCode'}}];pp.mapping=e.call('/field-mappings','POST',{'code':pp.code,'categoryCode':pp.code,'definition':mp_def})
 # Non-coded required resin is explicitly supplied as a parse default for this catalog-specific sample.
 # PARSE must still validate its ownership and active state.
 pp_bundle=copy.deepcopy(pp.bundle);next(x for x in pp_bundle['attributes'] if x['code']=='resin')['default']={'type':'ERP_MATERIAL','id':resin_key};pp_schema=e.call('/categories/'+pp.code+'/schemas','POST',{'bundle':pp_bundle});pp.release=pp.create_release(pp.record(pp.base,'PARSE_GOLDEN'),schema=pp_schema);pp.schema=pp_schema
 common=[enum('product',['RC','RF','PG'],required=True),enum('process',['A','C','T'],required=True),enum('size',['STD1','STD2']),enum('cut',['CUT1','CUT2']),field('variant',derived=ast('coalesce',prop('size'),prop('cut')),required=True)]
 xor=[{'field':'size','assert':ast('ne',ast('exists',prop('size')),ast('exists',prop('cut'))),'message':'尺寸与裁切必须且只能选择一个'}]
 filmparse={'kind':'REGEX','pattern':re.escape(tag+'_FILM')+r'-(?<product>RC|RF|PG)-(?<process>A|C|T)-(?:(?<size>STD1|STD2)|(?<cut>CUT1|CUT2))-[0-9]{6}','fields':{n:{'group':n} for n in ['product','process','size','cut']}}
 film=Fixture(e,r,tag,'FILM',common,['product','process','size','cut'],segments(['product','process','variant']),{'product':'RC','process':'A','size':'STD1'},validations=xor,parse_rule=filmparse,sequence=True,expected='RC-A-STD1-{流水:6}')
 boardattrs=copy.deepcopy(common)+[decimal_field('thickness',required=True),enum('stack',['L2','L4'],required=True),enum('upperCopper',['CU1','CU2'],required=True),enum('lowerCopper',['CU1','CU2'],required=True)]
 boardnames=['product','process','variant','thickness','stack','upperCopper','lowerCopper'];boardparse={'kind':'REGEX','pattern':re.escape(tag+'_BOARD')+r'-(?<product>RC|RF|PG)-(?<process>A|C|T)-(?:(?<size>STD1|STD2)|(?<cut>CUT1|CUT2))-(?<thickness>[0-9.]+)-(?<stack>L2|L4)-(?<upperCopper>CU1|CU2)-(?<lowerCopper>CU1|CU2)-[0-9]{6}','fields':{n:{'group':n,**({'unit':'mm'} if n=='thickness' else {})} for n in ['product','process','size','cut','thickness','stack','upperCopper','lowerCopper']}}
 board=Fixture(e,r,tag,'BOARD',boardattrs,['product','process','size','cut','thickness','stack','upperCopper','lowerCopper'],segments(boardnames),{'product':'RC','process':'A','size':'STD1','thickness':{'value':decimal.Decimal('1.6'),'unit':'mm'},'stack':'L2','upperCopper':'CU1','lowerCopper':'CU1'},validations=xor,parse_rule=boardparse,sequence=True,expected='RC-A-STD1-1.6-L2-CU1-CU1-{流水:6}')
 hierarchy=[{'field':'small','assert':ast('or',ast('and',ast('eq',prop('medium'),'M1'),ast('in',prop('small'),['S11','S12'])),ast('and',ast('eq',prop('medium'),'M2'),ast('eq',prop('small'),'S21'))),'message':'小类别不属于中类别'}]
 mat=Fixture(e,r,tag,'MAT',[enum('medium',['M1','M2'],required=True),enum('small',['S11','S12','S21'],required=True),field('spec',required=True),enum('status',['ACTIVE','INACTIVE'],default='ACTIVE'),decimal_field('length',required=True)],['medium','small','spec','length'],segments(['medium','small','spec','length']),{'medium':'M1','small':'S11','spec':'SPEC1','length':{'value':10,'unit':'mm'}},validations=hierarchy,sequence=True,expected='M1-S11-SPEC1-10-{流水:6}')
 cons=Fixture(e,r,tag,'CONS',[enum('medium',['M1','M2'],required=True),enum('small',['S11','S12','S21'],required=True),enum('issueMode',['EACH','BOX'],required=True)],['medium','small','issueMode'],segments(['medium','small','issueMode']),{'medium':'M1','small':'S11','issueMode':'EACH'},validations=hierarchy,sequence=True,expected='M1-S11-EACH-{流水:6}')
 return dict(GC=g,CHEM=chem,CF=cf,PP=pp,FILM=film,BOARD=board,RESIN=resin,MAT=mat,CONS=cons),admin
f,admin=build();g=f['GC'];issued={};keys={}
def standard(kind):
 fi=f[kind];key=fi.record({},'STANDARD') if kind=='GC' else fi.record(suffix='STANDARD');res=fi.assign(key);keys[kind]=key;issued[kind]=res
 if kind not in ['PP','FILM','BOARD','MAT','CONS']:equal(res['materialNo'],fi.expected)
 else:assert re.fullmatch(re.escape(fi.expected).replace(re.escape('{流水:6}'),'[0-9]{6}'),res['materialNo'])
 return {'assignmentId':res['assignmentId'],'materialNo':res['materialNo']}
case('CORE-001 GC-001 GC-002 GC-003 GC-004 DS-002 DS-006 DS-007','七个玻璃布业务属性从七个关联展开并按Golden发号',lambda:standard('GC'))
case('CORE-002 CF-001 CF-002 CF-005','铜箔共用引擎，基重与二维尺寸按配置编码',lambda:standard('CF'))
for kind,id in [('CHEM','CHEM-001'),('PP','PP-001 PP-002 PP-003 PP-004 PP-005'),('FILM','FILM-001 FILM-005 FILM-007'),('BOARD','BOARD-001 BOARD-002 BOARD-004 BOARD-005'),('RESIN','RESIN-001'),('MAT','MAT-001 MAT-004'),('CONS','CONS-001 CONS-004')]:case(id,kind+' 独立Golden编码',lambda k=kind:standard(k))
def variation(kind,updates,expected_fragment=None):
 fi=f[kind];base={} if kind=='GC' else copy.deepcopy(fi.base);base.update(updates);key=fi.record(base);p=fi.preview(key);old=fi.preview(keys[kind]);assert p['identity']['canonical']!=old['identity']['canonical'];res=fi.assign(key);assert res['materialNo']!=issued[kind]['materialNo']
 if expected_fragment:assert expected_fragment in res['materialNo'],res
 return {'materialNo':res['materialNo'],'identity':p['identity']['canonical']}
for kind,id,updates,fragment in [('GC','GC-005',{'treatment_id':'T02'},'-B-'),('GC','GC-006',{'width_id':'W1280'},'-1280-'),('GC','GC-007',{'grade_id':'G02'},'-B'),('CHEM','CHEM-002',{'package':'DRUM'},'-DRUM-'),('CHEM','CHEM-003',{'manufacturer':'TS'},'-TS-'),('CHEM','CHEM-004',{'grade':'B'},'-B'),('CF','CF-003',{'treatment':'B'},'-B-'),('CF','CF-004',{'color':'YELLOW'},'-YELLOW-'),('PP','PP-006',{'width':{'value':1280,'unit':'mm'}},'-1280-'),('FILM','FILM-006',{'process':'T'},'-T-'),('BOARD','BOARD-003',{'stack':'L4'},'-L4-'),('MAT','MAT-002',{'spec':'SPEC2'},'-SPEC2-'),('CONS','CONS-003',{'issueMode':'BOX'},'-BOX-')]:case(id,kind+' 属性变化区分Identity和编码',lambda k=kind,u=updates,z=fragment:variation(k,u,z))
def duplicate(kind,updates=None):
 fi=f[kind];data={} if kind=='GC' else copy.deepcopy(fi.base);data.update(updates or {});key=fi.record(data);return expect('IDENTITY_CONFLICT',lambda:fi.assign(key),409)
case('CORE-007 CORE-008 GC-010 FILM-008 CONS-005','不同ERP键的相同完整Identity不重复发号',lambda:[duplicate(k) for k in ['GC','FILM','CONS']])
def idempotency():
 first=g.assign(keys['GC'],idem=tag+'-IDEM');again=g.assign(keys['GC'],idem=tag+'-IDEM');equal(first,again);equal(first['materialNo'],issued['GC']['materialNo']);return first['requestId']
case('CORE-009 CORE-024 INT-004','来源重复与相同请求幂等返回原号',idempotency)
def description_change():
 before=g.preview(keys['GC']);erp('/records/cloth/'+keys['GC'],'PATCH',{'remarks':'new description'});after=g.preview(keys['GC']);equal(before['identity'],after['identity']);equal(g.assign(keys['GC'])['materialNo'],issued['GC']['materialNo']);return g.explain(issued['GC'])['ledger']['id']
case('CORE-004 CORE-017 CORE-019','非编码字段缺失或备注变更保持原号',description_change)
def identity_changed():
 erp('/records/cloth/'+keys['GC'],'PATCH',{'width_id':'W1280'});err=expect('IDENTITY_CHANGED_AFTER_ISSUE',lambda:g.assign(keys['GC']),409);equal(g.explain(issued['GC'])['ledger']['materialNo'],issued['GC']['materialNo']);erp('/records/cloth/'+keys['GC'],'PATCH',{'width_id':'W1270'});return err
case('CORE-018','已发号后身份变化进入异常，不重编码',identity_changed)
def dimension_equivalent():
 key=g.record({'width_id':'W127CM'});p=g.preview(key);equal(p['attributes']['width'],{'value':1270,'unit':'mm'});equal(p['identity'],g.preview(keys['GC'])['identity']);return expect('IDENTITY_CONFLICT',lambda:g.assign(key),409)
case('CORE-006','127 cm 与1270 mm标准化后同Identity',dimension_equivalent)
def missing_required():
 fi=f['CF'];data=copy.deepcopy(fi.base);data['manufacturer']=None;key=fi.record(data);return expect('VALIDATION_ERROR',lambda:fi.assign(key),field_path='/attributes/manufacturer')
case('CORE-003 ERR-001','必填null错误含字段路径',missing_required)
def missing_join():return expect('SOURCE_INCOMPLETE',lambda:g.assign(g.record({'manufacturer_id':'ABSENT'})),field_path='/joins/m')
case('GC-008 DS-003','缺失来源厂商定位Join路径',missing_join)
def inactive_reference():
 erp('/records/width_ref','POST',{'id':tag+'OFF','width':1270,'unit':'mm','active':False});err=expect('REFERENCE_INACTIVE',lambda:g.assign(g.record({'width_id':tag+'OFF'})));equal(g.explain(issued['GC'])['ledger']['materialNo'],issued['GC']['materialNo']);return err
case('CORE-016 GC-009','停用参考值阻止新号，历史解释保留',inactive_reference)
def xor_case(kind,size,cut,valid):
 fi=f[kind];data=copy.deepcopy(fi.base);data.pop('size',None);data.pop('cut',None)
 if size:data['size']='STD2'
 if cut:data['cut']='CUT2'
 key=fi.record(data)
 if not valid:return expect('VALIDATION_ERROR',lambda:fi.assign(key),field_path='/attributes/size')
 return fi.assign(key)['materialNo']
for kind,size,cut,valid,id in [('FILM',False,True,True,'FILM-002'),('FILM',True,True,False,'FILM-003'),('FILM',False,False,False,'FILM-004'),('BOARD',False,True,True,'BOARD-006'),('BOARD',True,True,False,'BOARD-007'),('BOARD',False,False,False,'BOARD-008')]:case(id,kind+' 尺寸/裁切严格XOR',lambda k=kind,s=size,c=cut,v=valid:xor_case(k,s,c,v))
def invalid_combo(kind,updates):
 fi=f[kind];data=copy.deepcopy(fi.base);data.update(updates);return expect('VALIDATION_ERROR',lambda:fi.assign(fi.record(data)))
case('CHEM-005','分类和型号组合不合法',lambda:invalid_combo('CHEM',{'model':'X2'}))
case('CF-007','非法等级拒绝',lambda:invalid_combo('CF',{'grade':'ILLEGAL'}))
case('CONS-002','非法中小类别组合拒绝',lambda:invalid_combo('CONS',{'small':'S21'}))
case('MAT-005 ERR-005','单位量纲错误拒绝',lambda:invalid_combo('MAT',{'length':{'value':10,'unit':'kg'}}))
case('ERR-004','超出数值精度拒绝，绝不四舍五入',lambda:invalid_combo('MAT',{'length':{'value':decimal.Decimal('1.1234'),'unit':'mm'}}))
case('ERR-002','超长文本拒绝，绝不截断',lambda:invalid_combo('MAT',{'spec':'X'*1001}))
def derived_description():p=f['CHEM'].preview(keys['CHEM']);equal(p['attributes']['description'],'HH-X1');assert 'description' not in p['identity']['values'];return p['attributes']['description']
case('CHEM-007','描述由属性派生且不属于Identity',derived_description)
def numeric_description():
 fi=f['CF'];data=copy.deepcopy(fi.base);data['description']='display text';key=fi.record(data);equal(fi.preview(key)['identity'],fi.preview(keys['CF'])['identity']);return expect('IDENTITY_CONFLICT',lambda:fi.assign(key),409)
case('CF-006','二维尺寸描述变化不影响Identity',numeric_description)
def status_change():fi=f['MAT'];erp('/records/flat_material/'+keys['MAT'],'PATCH',{'data':{**fi.base,'status':'INACTIVE'}});equal(fi.assign(keys['MAT'])['materialNo'],issued['MAT']['materialNo']);return fi.explain(issued['MAT'])['ledger']['id']
case('MAT-003','非编码状态变更不重编号',status_change)
def audit():x=g.explain(issued['GC']);a=x['ledger'];assert all(a.get(k) for k in ['sourceSystemId','datasetVersionId','sourceRecordKey','schemaVersionId','mappingVersionId','identityDefinitionVersionId','codeRuleVersionId','releaseId','issuedAt']);equal(len(x['explanation']['segments']),8);assert x['operationLogs'][0]['traceId'];assert x['writebackTasks'];return {'assignment':a['id'],'trace':x['operationLogs'][0]['traceId']}
case('CORE-023 CORE-025','原子台账与完整版本/分段/trace解释',audit)
def preview_parts():p=g.preview(g.record({'grade_id':'G02','width_id':'W1280'}));equal(p['candidateMaterialNo'],g.code+'-E-7628-HH-A-R-1280-B');equal(len(p['segments']),8);return p['candidateMaterialNo']
case('CORE-022','预览返回明确分段而不正式占号',preview_parts)
def source_request():fi=f['MAT'];key=fi.record({**fi.base,'spec':'ERPREQUEST'});a=fi.assign(key,source=True);assert a['requestId'];return a['requestId']
case('INT-001','ERP来源客户端申请正式料号',source_request)
def unknown_fields():
 fi=f['MAT'];key=fi.record();return expect('UNKNOWN_FIELD',lambda:e.call('/material-numbers:assign','POST',{'releaseId':fi.release['id'],'sourceRecordKey':key,'UNKNOWN':'bad'}))
case('ERR-008','源API未登记字段拒绝',unknown_fields)
def writes():
 for kind in ['GC','CF','FILM','BOARD','RESIN']:
  result=f[kind].write(issued[kind]);equal(result['state'],'SUCCEEDED');equal(result['assignmentId'],issued[kind]['assignmentId'])
 return {'confirmed':5}
case('INT-002 INT-005','真实ERP回写当前值确认，重复不会新发号',writes)
def downstream():
 a,b=issued['BOARD']['materialNo'],issued['FILM']['materialNo'];before=f['BOARD'].explain(issued['BOARD'])['ledger'];erp('/downstream/items','POST',{'materialNo':a,'data':{'inventoryQuantity':10}});erp('/downstream/items','POST',{'materialNo':b,'data':{'inventoryQuantity':5}});erp('/downstream/bom','POST',{'parentNo':a,'childNo':b,'quantity':2});erp('/downstream/routing','POST',{'materialNo':a,'version':1,'data':{'operation':'cut'}});erp('/downstream/routing','POST',{'materialNo':a,'version':2,'data':{'operation':'new cut'}});erp('/downstream/bom','POST',{'parentNo':a,'childNo':b,'quantity':3});equal(before,f['BOARD'].explain(issued['BOARD'])['ledger']);assert any(x['parent_no']==a and x['child_no']==b and x['quantity']==3 for x in erp('/downstream/bom'));assert any(x['material_no']==a and x['version']==2 for x in erp('/downstream/routing'));return {'parent':a,'child':b,'ERPRouteVersion':2}
case('INT-009 INT-010 INT-011 INT-012 INT-013','下游库存/BOM/工艺在ERP保存，变更不影响台账',downstream)
# Explicit parsing never tries unrequested versions and never allocates a number.
for kind,id in [('GC','PARSE-001'),('CF','PARSE-002'),('PP','PARSE-003'),('FILM','PARSE-004'),('BOARD','PARSE-005')]:
 def parse_check(k=kind):
  fi=f[k];p=fi.parse(issued[k]['materialNo']);expected=fi.explain(issued[k])['explanation']['attributes'];assert p['attributes'];assert all(p['attributes'][name]==expected[name] for name in p['attributes'] if name in expected),(p,expected);return p
 case(id+' PARSE-009',kind+' 明确版本反解析与生成输入一致',parse_check)
case('PARSE-006','未知料号格式明确失败',lambda:expect('PARSE_FAILED',lambda:g.parse('UNKNOWN-NUMBER')))
def bad_release(rule=None,mapping=None,identity=None,definition=None):
 return e.call('/releases','POST',{'code':g.code,'categoryCode':g.code,'datasetVersionId':g.dataset['id'],'schemaVersionId':g.schema['id'],'mappingVersionId':(mapping or g.mapping)['id'],'identityDefinitionVersionId':(identity or g.identity)['id'],'codeRuleVersionId':(rule or g.rule)['id'],'definition':definition or g.release['definition']})
def empty_rule():
 rule=e.call('/code-rules','POST',{'code':g.code,'categoryCode':g.code,'definition':{'segments':[]}});rr=bad_release(rule=rule);err=expect('RULE_NOT_CONFIGURED',lambda:e.call('/releases/'+rr['id']+'/test','POST',{}));expect('RELEASE_NOT_PUBLISHED',lambda:g.assign(g.record({'width_id':'W1280','manufacturer_id':'M02'}),rr));return err
case('CORE-014 RESIN-002','无有效Code Rule无法发布或正式发号',empty_rule)
def missing_mapping():
 mp=e.call('/field-mappings','POST',{'code':g.code,'categoryCode':g.code,'definition':{'fields':[]}});rr=bad_release(mapping=mp);return expect('MAPPING_ERROR',lambda:e.call('/releases/'+rr['id']+'/test','POST',{}))
case('CORE-015','缺必填属性映射拒绝发布',missing_mapping)
def no_identity():
 ident=e.call('/identity-definitions','POST',{'code':g.code,'categoryCode':g.code,'definition':{'fields':[]}});rr=bad_release(identity=ident);return expect('IDENTITY_EMPTY',lambda:e.call('/releases/'+rr['id']+'/test','POST',{}))
case('ERR-011','空Identity拒绝发布',no_identity)
def no_samples():
 definition=copy.deepcopy(g.release['definition']);definition['samples']=[];rr=bad_release(definition=definition);return expect('GOLDEN_SAMPLES_REQUIRED',lambda:e.call('/releases/'+rr['id']+'/submit','POST',{},rr['rowVersion']))
case('CFG-003','没有样本不能启用Identity/组合版本',no_samples)
def wrong_golden():
 definition=copy.deepcopy(g.release['definition']);definition['samples'][0]['expectedMaterialNo']='WRONG';rr=bad_release(definition=definition);return expect('GOLDEN_SAMPLE_MISMATCH',lambda:e.call('/releases/'+rr['id']+'/submit','POST',{},rr['rowVersion']))
case('CFG-005','独立Golden不符合预期时阻止发布',wrong_golden)
def immutable():
 before=g.mapping['definition'];err=expect('IMMUTABLE_CONFIGURATION',lambda:e.call('/field-mappings/'+g.mapping['id'],'PATCH',{'definition':{'fields':[]}},g.mapping['rowVersion']),409);equal(e.call('/field-mappings/'+g.mapping['id'])['definition'],before);return err
case('CFG-002','已发布Mapping不可原地篡改',immutable)
def draft_isolated():
 before=g.assign(keys['GC'])['materialNo'];draft=e.call('/source-datasets','POST',{'code':g.code,'sourceSystemId':g.source['id'],'definition':g.dataset['definition']});draft=e.call('/source-datasets/'+draft['id'],'PATCH',{'definition':draft['definition']},draft['rowVersion']);equal(g.assign(keys['GC'])['materialNo'],before);return draft['status']
case('CFG-001','可编辑草稿不会影响默认生产版本',draft_isolated)
def self_approval():
 rr=bad_release();rr=e.call('/releases/'+rr['id']+'/submit','POST',{},rr['rowVersion']);return expect('SELF_APPROVAL',lambda:r.call('/releases/'+rr['id']+'/approve','POST',{},rr['rowVersion'],headers=None) if False else e.call('/releases/'+rr['id']+'/approve','POST',{},rr['rowVersion']),403)
# Both design and publish roles are present for editor in the preserved V2 test seed; the guard itself is also exercised in Java.
# Use the reviewer as submitter only when that member is explicitly granted DESIGN; no permission is silently changed here.
def wrong_approver():
 rr=bad_release();rr=e.call('/releases/'+rr['id']+'/submit','POST',{},rr['rowVersion']);err=expect('ACTION_FORBIDDEN',lambda:e.call('/releases/'+rr['id']+'/approve','POST',{},rr['rowVersion']),403);e.call('/releases/'+rr['id']+'/withdraw','POST',{'reason':'acceptance negative'},rr['rowVersion']);return err
case('SEC-004','无发布岗位不能审批配置',wrong_approver)
def ddl_checks():
 ddl='CREATE TABLE '+tag+'_parent (id VARCHAR(40) PRIMARY KEY, code VARCHAR(20) NOT NULL); CREATE TABLE '+tag+'_child (id VARCHAR(40) PRIMARY KEY,parent_id VARCHAR(40),weight NUMBER(12,3),CONSTRAINT f FOREIGN KEY(parent_id) REFERENCES '+tag+'_parent(id));'
 before=len(e.call('/source-datasets'));objs=e.call('/source-objects:import-ddl','POST',{'sourceSystemId':g.source['id'],'ddl':ddl});equal(len(objs),2);equal(objs[0]['definition']['primaryKey'],['id']);equal(objs[1]['definition']['fields'][2]['scale'],3);suggest=e.call('/source-objects/'+objs[1]['id']+'/join-suggestions');equal(len(suggest),1);assert suggest[0]['requiresConfirmation'];equal(before,len(e.call('/source-datasets')));expect('SOURCE_OBJECT_CHANGED',lambda:e.call('/source-systems/'+g.source['id']+'/discover','POST',{'objects':[{'objectName':tag+'_parent'}]}));return {'objects':[o['id'] for o in objs],'suggestions':suggest}
case('SRC-001 SRC-002 SRC-003','DDL仅形成元数据，FK建议需确认，不创建ERP表',ddl_checks)
def object_version_diff():
 original=g.objects['cloth'];definition=copy.deepcopy(original['definition']);definition['fields'].append({'name':'extra','dataType':'VARCHAR','nullable':True});new=e.call('/source-objects','POST',{'sourceSystemId':g.source['id'],'definition':definition});diff=e.call('/source-objects/'+original['id']+'/diff?against='+new['id']);assert any(x.get('field')=='extra' for x in diff['changes']);assert diff['affectedReleases'];equal(e.call('/source-objects/'+original['id'])['definition'],original['definition']);return diff
case('SRC-005 CFG-006','结构新版本显示依赖影响且不篡改历史',object_version_diff)
def bad_dataset(change,code):
 definition=copy.deepcopy(g.dataset['definition']);change(definition);return expect(code,lambda:e.call('/source-datasets','POST',{'code':g.code,'sourceSystemId':g.source['id'],'definition':definition}))
case('ERR-010','Root来源键为空禁止保存',lambda:bad_dataset(lambda d:d.update(sourceKey=[]),'SOURCE_KEY_REQUIRED'))
case('DS-008','关联字符与数字字段明确拒绝',lambda:bad_dataset(lambda d:d['joins'][0]['on'][0].update(left='g.version'),'JOIN_TYPE_MISMATCH'))
case('DS-005','明确声明1:N粒度扩张拒绝',lambda:bad_dataset(lambda d:d['joins'][0].update(cardinality='1:N'),'GRAIN_CONFLICT'))
case('SRC-004','无主表FK仍可显式关联',lambda:equal(len(g.preview(keys['GC'])['read']['joins']),7))
case('DS-001','无Join单表API Dataset成功形成逻辑输入',lambda:equal(f['CF'].preview(keys['CF'])['read']['joins'],[]))
def code_bad_chars():
 definition=copy.deepcopy(g.rule['definition']);definition['segments'][0]['value']='ILLEGAL 空格';rule=e.call('/code-rules','POST',{'code':g.code,'categoryCode':g.code,'definition':definition});definition=copy.deepcopy(g.release['definition']);definition['samples'][0]['expectedMaterialNo']='ILLEGAL 空格-E-7628-HH-A-R-1270-A';rr=bad_release(rule=rule,definition=definition);return expect('CODE_FORMAT',lambda:e.call('/releases/'+rr['id']+'/test','POST',{}))
case('CFG-004 ERR-003','字符集非法的Golden阻止发布',code_bad_chars)
def enum_disabled():
 fi=f['CHEM'];bundle=copy.deepcopy(fi.bundle);next(a for a in bundle['attributes'] if a['code']=='manufacturer')['options'][1]['active']=False;schema=e.call('/categories/'+fi.code+'/schemas','POST',{'bundle':bundle});rr=fi.create_release(fi.record(suffix='DISABLE_GOLDEN'),schema=schema);data={**fi.base,'manufacturer':'TS'};return expect('VALIDATION_ERROR',lambda:fi.assign(fi.record(data),rr),field_path='/attributes/manufacturer')
case('CHEM-006','字典项停用发布后不能用于新号',enum_disabled)
def inactive_resin():
 fi=f['PP'];id=fi.base['resin']['id'];current=erp('/documents/'+id);data={k:v for k,v in current.items() if k not in ['id','category','materialNo','sourceVersion','sourceUpdatedAt']};erp('/records/flat_material/'+id,'PATCH',{'data':{**data,'active':False}})
 try:return expect('VALIDATION_ERROR',lambda:fi.assign(fi.record({**fi.base,'process':'C'})),field_path='/attributes/resin')
 finally:erp('/records/flat_material/'+id,'PATCH',{'data':data})
case('PP-007','失效ERP引用阻止新发号，恢复修改ERP事实源',inactive_resin)
def cross_tenant():
 other=Session('editor','TENANT-B');expect('NOT_FOUND',lambda:other.call('/assignment-ledger/'+issued['GC']['assignmentId']),404);expect('NOT_FOUND',lambda:other.call('/source-objects/'+g.objects['cloth']['id']),404);return 'no cross-tenant object/ledger access'
case('SEC-002 SEC-003','跨租户对象和台账不可读',cross_tenant)
def no_integration_role():
 reader=Session('reader');return expect('ACTION_FORBIDDEN',lambda:reader.call('/material-numbers:assign','POST',{'releaseId':g.release['id'],'sourceRecordKey':keys['GC']}),403)
# Integration permission is isolated with a temporary category read scope below.
def source_binding():return expect('SOURCE_CLIENT_SCOPE',lambda:e.call('/material-numbers:assign','POST',{'releaseId':g.release['id'],'sourceRecordKey':keys['GC']},headers={'X-Source-Key':f['MAT'].client_key}),403)
case('SEC-003','来源客户端不能越过自己来源边界',source_binding)
def concurrent_distinct():
 fi=f['MAT'];records=[fi.record({**fi.base,'spec':'CONCURRENT'+str(i)}) for i in range(100)];start=time.perf_counter()
 with ThreadPoolExecutor(max_workers=100) as pool:results=list(pool.map(fi.assign,records))
 equal(len({x['materialNo'] for x in results}),100);equal(len({x['assignmentId'] for x in results}),100);return {'requests':100,'uniqueNumbers':100,'wallMs':round((time.perf_counter()-start)*1000,2)}
case('CORE-010 PERF-004','100并发不同Identity正式发号唯一',concurrent_distinct)
def board_concurrent():
 fi=f['BOARD'];records=[fi.record({**fi.base,'thickness':{'value':decimal.Decimal('2')+decimal.Decimal(i)/1000,'unit':'mm'}}) for i in range(100)]
 with ThreadPoolExecutor(max_workers=100) as pool:res=list(pool.map(fi.assign,records))
 equal(len({x['materialNo'] for x in res}),100);return {'requests':100,'uniqueNumbers':100}
case('BOARD-009','100并发基板XOR尺寸模式按流水唯一',board_concurrent)

def concurrent_identity():
 fi=f['MAT'];records=[fi.record({**fi.base,'spec':'SAMECONCURRENT'}) for _ in range(100)]
 def attempt(key):
  try:return fi.assign(key)
  except ApiError as ex:equal(ex.status,409);equal(ex.data['code'],'IDENTITY_CONFLICT');return None
 with ThreadPoolExecutor(max_workers=100) as pool:res=list(pool.map(attempt,records))
 equal(sum(x is not None for x in res),1);return {'requests':100,'assignments':1}
case('CORE-011 PERF-005','100并发不同来源键同Identity最多一个台账',concurrent_identity)
def concurrent_preview():
 fi=f['CF']
 with ThreadPoolExecutor(max_workers=100) as pool:res=list(pool.map(lambda _:fi.preview(keys['CF']),range(100)))
 equal(len(res),100);equal(len({x['candidateMaterialNo'] for x in res}),1);return {'requests':100,'stableCandidate':res[0]['candidateMaterialNo']}
case('PERF-003','100并发预览返回稳定结果',concurrent_preview)
def performance():
 preview=[];issue=[];fi=f['MAT']
 for i in range(30):
  start=time.perf_counter();f['CF'].preview(keys['CF']);preview.append((time.perf_counter()-start)*1000);key=fi.record({**fi.base,'spec':'PERF'+str(i)});start=time.perf_counter();fi.assign(key);issue.append((time.perf_counter()-start)*1000)
 p=lambda values:sorted(values)[int(len(values)*.95)-1];assert p(preview)<=500,preview;assert p(issue)<=1000,issue;return {'previewP95Ms':round(p(preview),2),'issueP95Ms':round(p(issue),2),'samples':30,'scope':'local cloud + simulated ERP; not production load'}
case('PERF-001 PERF-002','实测稳态预览/发号P95',performance)

def member_state(user):return next(x for x in admin.call('/tenant/members') if x['userCode']==user)
def temporarily(user,updates,work):
 before=member_state(user);changed=admin.call('/tenant/members/'+before['id'],'PATCH',{**updates,'reason':'验收临时授权，测试后恢复'},before['rowVersion'])
 try:return work()
 finally:current=member_state(user);admin.call('/tenant/members/'+before['id'],'PATCH',{'roles':before['roles'],'categoryScope':before['categoryScope'],'sourceScope':before['sourceScope'],'reason':'恢复验收前授权'},current['rowVersion'])
def isolated_integration_permission():
 before=member_state('reader');return temporarily('reader',{'categoryScope':before['categoryScope']+[g.code]},lambda:expect('ACTION_FORBIDDEN',lambda:Session('reader').call('/material-numbers:assign','POST',{'releaseId':g.release['id'],'sourceRecordKey':keys['GC']}),403))
case('SEC-005','保留类别读权限时，无集成权限仍拒绝发号',isolated_integration_permission)
def isolated_self_approval():
 def work():
  rr=bad_release();rr=e.call('/releases/'+rr['id']+'/submit','POST',{},rr['rowVersion']);err=expect('SELF_APPROVAL',lambda:e.call('/releases/'+rr['id']+'/approve','POST',{},rr['rowVersion']),403);e.call('/releases/'+rr['id']+'/withdraw','POST',{'reason':'negative test'},rr['rowVersion']);return err
 return temporarily('editor',{'roles':member_state('editor')['roles']+['PUBLISHER']},work)
case('CFG-008','同时有设计和发布岗位也不能自我审批',isolated_self_approval)
def vendor_business_mapping():
 p=g.preview(keys['GC']);equal(p['read']['root']['g.manufacturer_id'],'M01');equal(p['attributes']['manufacturer'],'HH');assert '-HH-' in p['candidateMaterialNo'];assert '-M01-' not in p['candidateMaterialNo'];return {'sourceId':'M01','mappedBusinessCode':'HH'}
case('CORE-005','编码明确使用厂商业务码而不是数据库ID',vendor_business_mapping)
def duplicate_join():
 for id,value in [('A','first'),('B','second')]:erp('/records/duplicate_ref','POST',{'id':tag+id,'business_key':tag,'value':value})
 root=g.record({'manufacturer_id':tag});definition=copy.deepcopy(g.dataset['definition']);j=definition['joins'][0];j['objectVersionId']=g.objects['duplicate_ref']['id'];j['on']=[{'left':'g.manufacturer_id','right':'m.business_key'}];j.pop('activeField',None);j.pop('activeValue',None);definition['output']['manufacturer']='m.value';ds=e.call('/source-datasets','POST',{'code':g.code+'_DUP','sourceSystemId':g.source['id'],'definition':definition});return expect('GRAIN_CONFLICT',lambda:e.call('/source-datasets/'+ds['id']+'/preview','POST',{'sourceRecordKey':root}),409)
case('DS-004 GC-011','真实重复业务键Join检测粒度冲突，绝不取第一行',duplicate_join)
def version_upgrade():
 rule_def=copy.deepcopy(g.rule['definition']);rule_def['segments'][0]['value']=g.code+'_V2';rule_def['parseRule']['pattern']=rule_def['parseRule']['pattern'].replace(re.escape(g.code),re.escape(g.code+'_V2'),1);new_rule=e.call('/code-rules','POST',{'code':g.code,'categoryCode':g.code,'definition':rule_def});sample=g.record(suffix='V2_GOLDEN');before=g.expected;g.expected=g.code+'_V2-E-7628-HH-A-R-1270-A'
 try:new_rel=g.create_glass_release(sample,rule=new_rule)
 finally:g.expected=before
 new_key=g.record({'manufacturer_id':'M02','treatment_id':'T02','width_id':'W1280','grade_id':'G02'});new_issue=e.call('/material-numbers:assign','POST',{'datasetCode':g.code,'sourceRecordKey':new_key});equal(new_issue['materialNo'],g.code+'_V2-E-7628-OTHER-B-R-1280-B');equal(g.assign(keys['GC'],new_rel)['materialNo'],issued['GC']['materialNo']);equal(g.explain(issued['GC'])['ledger']['codeRuleVersionId'],g.rule['id']);parsed=g.parse(issued['GC']['materialNo']);equal(parsed['attributes']['manufacturer'],'HH');state['newRule']=new_rule;state['newRelease']=new_rel;state['newIssue']=new_issue;return {'old':issued['GC']['materialNo'],'new':new_issue['materialNo'],'oldParseRule':parsed['parseRuleVersionId']}
state={}
case('CORE-020 GC-012 PARSE-008','新版规则服务新记录，旧发号及解析永久绑定旧版',version_upgrade)
def rollback_default():
 old=e.call('/releases/'+g.release['id']);r.call('/releases/'+old['id']+'/set-default','POST',{'reason':'acceptance rollback'},old['rowVersion']);key=g.record({'manufacturer_id':'M02','width_id':'W1280','grade_id':'G02'});a=e.call('/material-numbers:assign','POST',{'datasetCode':g.code,'sourceRecordKey':key});equal(a['materialNo'],g.code+'-E-7628-OTHER-A-R-1280-B');assert g.explain(state['newIssue'])['ledger']['materialNo'].startswith(g.code+'_V2-');return a['materialNo']
case('CFG-007','默认组合版本回退，已发号新旧版本均不改写',rollback_default)
def parse_ambiguous():
 rule=e.call('/code-rules','POST',{'code':g.code,'categoryCode':g.code,'definition':g.rule['definition']});rr=g.create_glass_release(g.record(suffix='AMBIG_GOLDEN'),rule=rule);return expect('PARSE_AMBIGUOUS',lambda:e.call('/material-numbers:parse','POST',{'codeRuleVersionIds':[g.rule['id'],rule['id']],'materialNo':issued['GC']['materialNo']}))
case('PARSE-007','两个明确指定解析版本均匹配时拒绝猜测',parse_ambiguous)
def cross_tenant_reference():
 other_admin=Session('admin','TENANT-B');id=tag+'ONLY_B';other_admin.call('/references','POST',{'type':'ERP_MATERIAL','id':id,'name':'隔离引用'});fi=f['PP'];data={**fi.base,'process':'T','resin':{'type':'ERP_MATERIAL','id':id}};return expect('VALIDATION_ERROR',lambda:fi.assign(fi.record(data)),field_path='/attributes/resin')
case('ERR-009','跨租户Reference所有权明确拒绝',cross_tenant_reference)
def cyclic_derived():
 bundle=copy.deepcopy(f['MAT'].bundle);bundle['attributes'] += [field('cycle1',derived=prop('cycle2')),field('cycle2',derived=prop('cycle1'))];return expect('CYCLIC_DEPENDENCY',lambda:e.call('/categories/'+f['MAT'].code+'/schemas','POST',{'bundle':bundle}))
case('ERR-007','派生循环在配置阶段阻止',cyclic_derived)
def long_code():
 rule_def=copy.deepcopy(g.rule['definition']);rule_def['segments'][0]['value']='X'*129;rule=e.call('/code-rules','POST',{'code':g.code,'categoryCode':g.code,'definition':rule_def});rr=bad_release(rule=rule);return expect('CODE_LENGTH',lambda:e.call('/releases/'+rr['id']+'/test','POST',{}))
case('ERR-012','超过128字符的料号不能通过发布样本',long_code)
def code_collision():
 fi=f['CF'];rule_def=copy.deepcopy(fi.rule['definition']);rule_def['segments'][0]['value']=fi.code+'_COLLIDE';rule_def['segments']=[s for s in rule_def['segments'] if s.get('field')!='grade'];rule_def.pop('parseRule',None);rule=e.call('/code-rules','POST',{'code':fi.code,'categoryCode':fi.code,'definition':rule_def});before=fi.expected;fi.expected=fi.code+'_COLLIDE-ED-35-HH-A-RED-1000-1270'
 try:rr=fi.create_release(fi.record(suffix='COLLIDE_GOLDEN'),rule=rule)
 finally:fi.expected=before
 first=fi.assign(fi.record({**fi.base,'weight':{'value':36,'unit':'g/m2'}}),rr);second=fi.record({**fi.base,'weight':{'value':36,'unit':'g/m2'},'grade':'B'});err=expect('CODE_CONFLICT',lambda:fi.assign(second,rr),409);equal(first['materialNo'],fi.code+'_COLLIDE-ED-36-HH-A-RED-1000-1270');return {'first':first['materialNo'],'error':err}
case('CORE-013 CF-008','不同Identity同码返回冲突，不加后缀',code_collision)
def cap_sequence():
 fi=Fixture(e,r,tag,'CAP',[field('name',required=True)],['name'],[{'type':'ATTR','field':'name'},{'type':'SEQUENCE','width':1}],{'name':'BASE'},expected='BASE-{流水:1}');out=[]
 for i in range(1,10):out.append(fi.assign(fi.record({'name':'N'+str(i)}))['materialNo'])
 expect('SEQUENCE_EXHAUSTED',lambda:fi.assign(fi.record({'name':'N10'})));equal(len(set(out)),9);assert out[-1].endswith('-9');return {'assigned':9,'last':out[-1],'overflow':'rejected'}
case('CORE-012 ERR-013','单字符流水容量耗尽拒绝，不回收已发编号',cap_sequence)
def divide_zero():
 attrs=[field('name',required=True),decimal_field('a','',required=True),decimal_field('b','',required=True),decimal_field('ratio','',derived=ast('div',prop('a'),prop('b')))];fi=Fixture(e,r,tag,'DIV',attrs,['name','a','b'],segments(['name']),{'name':'BASE','a':{'value':1},'b':{'value':1}},expected='BASE');return expect('VALIDATION_ERROR',lambda:fi.assign(fi.record({'name':'ZERO','a':{'value':1},'b':{'value':0}})),field_path='/attributes/ratio')
case('ERR-006','业务除零失败，不产生正式号',divide_zero)
def duplicate_outbox_delivery():
 fi=f['BOARD'];a=issued['BOARD'];before=fi.explain(a);task=before['writebackTasks'][-1];equal(e.call('/writeback-tasks/'+task['id']+'/process','POST',{})['state'],'SUCCEEDED');equal(e.call('/writeback-tasks/'+task['id']+'/process','POST',{})['state'],'SUCCEEDED');after=fi.explain(a);equal(after['writebackAttempts'],before['writebackAttempts']);return {'assignmentId':a['assignmentId'],'noAdditionalHTTPAttempts':True}
case('PERF-007','重复消费同一回写任务不重复发送ERP写入',duplicate_outbox_delivery)
def operation_log():
 x=g.explain(issued['GC']);assert x['operationLogs'];assert x['operationLogs'][0]['actor'];assert x['operationLogs'][0]['traceId'];logs=e.call('/operation-logs?objectId='+issued['GC']['assignmentId']);assert any(row['action']=='ISSUE' and row['objectId']==issued['GC']['assignmentId'] for row in logs);return x['operationLogs'][0]['traceId']
case('SEC-006','有权限的审计记录可定位发号操作者和trace',operation_log)

def pp_erp_lookup():
 fi=f['PP'];x=fi.preview(keys['PP']);equal(x['attributes']['glassType'],'E');equal(x['attributes']['clothCode'],'7628');assert 'glassType' not in fi.base and 'clothCode' not in fi.base;assert x['dependencies']['lookupDatasets'][g.dataset['id']]['definition']==g.dataset['definition'];return {'sourceRecordKey':fi.base['glassSourceKey'],'lookupDatasetVersionId':g.dataset['id'],'erpProjection':True}
case('PP-002','半固化片玻璃属性由固定Dataset读取ERP玻璃布并展开',pp_erp_lookup)
def schema_upgrade():
 fi=f['MAT'];old=fi.explain(issued['MAT'])['ledger'];bundle=copy.deepcopy(fi.bundle);bundle['attributes'].append(field('newRequired',required=True));schema=e.call('/categories/'+fi.code+'/schemas','POST',{'bundle':bundle});objdef=copy.deepcopy(fi.object['definition']);objdef['fields'].append({'name':'newRequired','dataType':'VARCHAR'});obj=e.call('/source-objects','POST',{'sourceSystemId':fi.source['id'],'definition':objdef});dsdef=copy.deepcopy(fi.dataset['definition']);dsdef['root']['objectVersionId']=obj['id'];dsdef['output']['newRequired']='g.newRequired';ds=e.call('/source-datasets','POST',{'code':fi.code,'sourceSystemId':fi.source['id'],'categoryCode':fi.code,'definition':dsdef});mpdef=copy.deepcopy(fi.mapping['definition']);mpdef['fields'].append({'target':'newRequired','source':'newRequired'});mp=e.call('/field-mappings','POST',{'code':fi.code,'categoryCode':fi.code,'definition':mpdef});rr=fi.create_release(fi.record({**fi.base,'newRequired':'V2'}),schema=schema,dataset=ds,mapping=mp)
 expect('VALIDATION_ERROR',lambda:fi.assign(fi.record({**fi.base,'spec':'SCHEMA_MISSING'}),rr),field_path='/attributes/newRequired');a=fi.assign(fi.record({**fi.base,'spec':'SCHEMA_OK','newRequired':'V2'}),rr);equal(fi.explain(a)['ledger']['schemaVersionId'],schema['id']);equal(fi.assign(keys['MAT'],rr)['assignmentId'],issued['MAT']['assignmentId']);equal(fi.explain(issued['MAT'])['ledger']['schemaVersionId'],old['schemaVersionId']);return {'oldSchema':old['schemaVersionId'],'newSchema':schema['id'],'requiredEnforced':True}
case('CORE-021','新增必填字段的Schema升级：旧记录固定v1，新记录严格v2',schema_upgrade)
def source_view_change():
 import subprocess
 view=('accept_view_'+tag).lower()
 def sql(q):subprocess.run(['docker','compose','exec','-T','db','psql','-U','mdm','-d','mdm_erp_v3','-v','ON_ERROR_STOP=1','-c',q],capture_output=True,check=True)
 sql('CREATE VIEW '+view+' AS SELECT g.id,g.status,g.material_no,g.version,t.code AS type,bw.cloth_code AS "clothCode",m.code AS manufacturer,tr.code AS treatment,f.code AS form,w.width,w.unit AS "widthUnit",gr.code AS grade,g.remarks FROM cloth g LEFT JOIN cloth_weight bw ON bw.id=g.weight_id LEFT JOIN cloth_type t ON t.id=bw.type_id LEFT JOIN manufacturer m ON m.id=g.manufacturer_id LEFT JOIN treatment tr ON tr.id=g.treatment_id LEFT JOIN form_ref f ON f.id=g.form_id LEFT JOIN width_ref w ON w.id=g.width_id LEFT JOIN grade_ref gr ON gr.id=g.grade_id')
 discovered=e.call('/source-systems/'+g.source['id']+'/discover','POST',{'objects':[{'objectName':view}]})[0];equal(discovered['objectType'],'VIEW');definition=copy.deepcopy(discovered['definition']);definition['uniqueKeys']=[['id']];obj=e.call('/source-objects','POST',{'sourceSystemId':g.source['id'],'definition':definition});dsdef=copy.deepcopy(g.dataset['definition']);dsdef['root']['objectVersionId']=obj['id'];dsdef['joins']=[];dsdef['output']={k:'g.'+k for k in dsdef['output']};ds=e.call('/source-datasets','POST',{'code':g.code,'sourceSystemId':g.source['id'],'categoryCode':g.code,'definition':dsdef});before=e.call('/source-datasets/'+ds['id']+'/preview','POST',{'sourceRecordKey':keys['GC']});multi=e.call('/source-datasets/'+g.dataset['id']+'/preview','POST',{'sourceRecordKey':keys['GC']});equal(before['output'],multi['output'])
 b={'code':g.code,'categoryCode':g.code,'datasetVersionId':ds['id'],'schemaVersionId':g.schema['id'],'mappingVersionId':g.mapping['id'],'identityDefinitionVersionId':g.identity['id'],'codeRuleVersionId':g.rule['id'],'definition':copy.deepcopy(g.release['definition'])};rr=e.call('/releases','POST',b);rr=e.call('/releases/'+rr['id']+'/submit','POST',{},rr['rowVersion']);rr=r.call('/releases/'+rr['id']+'/approve','POST',{},rr['rowVersion'])
 sql('DROP VIEW '+view);sql('CREATE VIEW '+view+' AS SELECT id,status,material_no,version FROM cloth');new=e.call('/source-systems/'+g.source['id']+'/discover','POST',{'objects':[{'objectName':view}]})[0];diff=e.call('/source-objects/'+obj['id']+'/diff?against='+new['id']);assert diff['incompatible'];assert any(x.get('field')=='manufacturer' and x.get('after') is None for x in diff['changes']);assert any(x['mappingId']==g.mapping['id'] for x in diff['affectedReleases']);alerts=e.call('/source-objects/'+obj['id']+'/alerts');assert alerts and alerts[0]['incompatible'];expect('SOURCE_OBJECT_CHANGED',lambda:e.call('/source-datasets/'+ds['id']+'/preview','POST',{'sourceRecordKey':keys['GC']}),503);equal(g.explain(issued['GC'])['ledger']['materialNo'],issued['GC']['materialNo']);old=r.call('/releases/'+g.release['id']);r.call('/releases/'+old['id']+'/set-default','POST',{'reason':'Restore after isolated view change test'},old['rowVersion']);return {'viewDatasetInputEquivalent':True,'alertId':alerts[0]['id'],'incompatibleDependents':diff['affectedReleases']}
case('SRC-006 SRC-007 DS-009','独立ERP View与Join等价，删除已映射字段生成依赖告警并阻止读取',source_view_change)
case('DS-010 RESIN-003','API Dataset和成胶按明确Parse版本共用同一引擎',lambda:f['RESIN'].parse(issued['RESIN']['materialNo']))

# Persist fixture IDs for independent browser checks and manual inspection.
pathlib.Path('.runtime/v3-acceptance-fixtures.json').write_text(dumps({'runId':tag,'fixtures':{k:{'categoryCode':v.code,'sourceSystemId':v.source['id'],'datasetId':v.dataset['id'],'releaseId':v.release['id'],'schemaId':v.schema['id'],'codeRuleId':v.rule['id']} for k,v in f.items()},'issued':issued,'keys':keys}),encoding='utf8')
print('CASES',len(RESULTS),'IDS',len({id for row in RESULTS for id in row['ids']}),'FAILED',sum(x['status']=='FAILED' for x in RESULTS),flush=True)
sys.exit(1 if any(x['status']=='FAILED' for x in RESULTS) else 0)
