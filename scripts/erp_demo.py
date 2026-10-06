"""Idempotently provision development ERP glass metadata through normal two-person MDM publication."""
import sys, os, json, uuid, urllib.request, http.cookiejar
from pathlib import Path
sys.path.insert(0, '/workspace/.tools/python')
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'erp-v3'))
import psycopg
from psycopg.rows import dict_row
from psycopg import sql
import workflow

CODE = 'ERP_GLASS_DEMO'
ERP = 'http://127.0.0.1:9092'
BASE = 'http://127.0.0.1:' + os.environ.get('PORT', '8080')
TENANT = 'TENANT-A'

def connect():
    return psycopg.connect(host='localhost',port=5432,dbname='mdm_erp_v3',user=os.environ.get('ERP_SIM_USER','mdm'),password=os.environ.get('ERP_SIM_PASSWORD','mdm_dev_only'),row_factory=dict_row)
class Session:
    def __init__(self, user):
        self.opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.call('/api/dev/login', 'POST', {'userCode':user})
    def call(self,path,method='GET',body=None,version=None):
        headers={'X-Tenant-Code':TENANT,'Idempotency-Key':str(uuid.uuid4()),'X-Request-ID':str(uuid.uuid4())}
        if body is not None:headers['Content-Type']='application/json'
        if version is not None:headers['If-Match']=str(version)
        req=urllib.request.Request(BASE+(path if path.startswith('/api/') else '/api/v1'+path),None if body is None else json.dumps(body).encode(),headers,method=method)
        with self.opener.open(req,timeout=30) as res:return json.load(res)

def main():
    workflow.initialize(connect)
    e=Session('editor')
    published=[r for r in e.call('/releases') if r['code']==CODE and r['status']=='PUBLISHED']
    if published:
        print('ERP 玻璃布演示发布版本已存在，保留当前规则与默认版本。')
    else:
        if not any(c['code']==CODE for c in e.call('/categories')):
            e.call('/categories','POST',{'code':CODE,'name':'ERP 玻璃布演示'})
        # On interrupted setup resume an existing source rather than duplicating its code.
        source=next((s for s in e.call('/source-systems') if s['code']==CODE),None)
        if source is None:
            source=e.call('/source-systems','POST',{'code':CODE,'type':'DATABASE','clientKey':os.environ['ERP_DEMO_SOURCE_KEY'],'connectionProfile':{'jdbcUrl':'jdbc:postgresql://localhost:5432/mdm_erp_v3','userEnv':'ERP_READ_USER','passwordEnv':'ERP_READ_PASSWORD','writebackBaseUrl':ERP,'allowManualAssign':False}})
        e.call('/source-systems/'+source['id']+'/test','POST',{})
        if source['status']!='ACTIVE':source=e.call('/source-systems/'+source['id'],'PATCH',{'status':'ACTIVE'},source['rowVersion'])
        tables=['cloth','manufacturer','cloth_weight','cloth_type','treatment','form_ref','width_ref','grade_ref']
        objects={o['objectName']:o for o in e.call('/source-systems/'+source['id']+'/discover','POST',{'objects':[{'objectName':n} for n in tables]})}
        joins=[]
        for table,alias,left in [('manufacturer','m','g.manufacturer_id'),('cloth_weight','bw','g.weight_id'),('cloth_type','t','bw.type_id'),('treatment','tr','g.treatment_id'),('form_ref','f','g.form_id'),('width_ref','w','g.width_id'),('grade_ref','gr','g.grade_id')]:
            joins.append({'objectVersionId':objects[table]['id'],'alias':alias,'type':'LEFT','cardinality':'N:1','nullPolicy':'ERROR','activeField':'active','activeValue':True,'on':[{'left':left,'right':alias+'.id'}]})
        output={'type':'t.code','clothCode':'bw.cloth_code','basisWeight':'bw.weight','basisWeightUnit':'bw.unit','manufacturer':'m.code','treatment':'tr.code','form':'f.code','width':'w.width','widthUnit':'w.unit','grade':'gr.code','remarks':'g.remarks'}
        ds=e.call('/source-datasets','POST',{'code':CODE,'categoryCode':CODE,'sourceSystemId':source['id'],'definition':{'root':{'objectVersionId':objects['cloth']['id'],'alias':'g'},'sourceKey':['g.id'],'grain':'ONE_ROOT_ROW_ONE_MATERIAL','joins':joins,'output':output,'existingNoField':'g.material_no','sourceVersionField':'g.version','candidateFilter':{'op':'eq','args':[{'field':'g.status'},CODE]},'incremental':{'strategy':'REQUEST_TRIGGER','referenceStrategy':'RESCAN_UNISSUED'}}})
        identity=['type','clothCode','basisWeight','manufacturer','treatment','form','width','grade']
        attrs=[{'code':n,'type':'STRING','required':True,'searchable':True} for n in identity if n not in ['width','basisWeight']]
        attrs += [{'code':n,'type':'DECIMAL','unit':u,'precision':12,'scale':3,'min':0,'required':True,'searchable':True} for n,u in [('width','mm'),('basisWeight','g/m2')]]
        attrs.append({'code':'remarks','type':'STRING'})
        rule={'separator':'','segments':[{'type':'CONST','value':'GC'},{'type':'SEQUENCE','name':'MAIN','width':6,'reset':'NEVER'}]}
        schema=e.call('/categories/'+CODE+'/schemas','POST',{'bundle':{'attributes':attrs,'validations':[],'codeRule':rule}})
        mapping=e.call('/field-mappings','POST',{'code':CODE,'categoryCode':CODE,'definition':{'fields':[{'source':n,'target':n,**({'unitSource':n+'Unit'} if n in ['width','basisWeight'] else {})} for n in identity+['remarks']]}})
        ident=e.call('/identity-definitions','POST',{'code':CODE,'categoryCode':CODE,'definition':{'fields':[{'field':n,'allowNull':False} for n in identity]}})
        code=e.call('/code-rules','POST',{'code':CODE,'categoryCode':CODE,'definition':rule})
        with connect() as c:
            # A source sample is needed for Golden testing; it is not itself issued.
            c.execute('''insert into cloth(id,status,weight_id,manufacturer_id,treatment_id,width_id,form_id,grade_id,remarks)
                values(%s,%s,'BW210','M01','T01','W1270','F01','G01','发布验证样本，未占号') on conflict(id) do nothing''',(CODE+'-GOLDEN',CODE))
        release=e.call('/releases','POST',{'code':CODE,'categoryCode':CODE,'datasetVersionId':ds['id'],'schemaVersionId':schema['id'],'mappingVersionId':mapping['id'],'identityDefinitionVersionId':ident['id'],'codeRuleVersionId':code['id'],'definition':{'samples':[{'sourceRecordKey':CODE+'-GOLDEN','expectedMaterialNo':'GC{流水:6}'}],'writeback':{'baseUrl':ERP,'path':'/writeback/cloth/{key}','queryPath':'/writeback/cloth/{key}','contractPath':'/contract','businessValidationPath':'/contract/validate','conditionalEmptyWrite':True,'supportsIdempotency':True}}})
        e.call('/releases/'+release['id']+'/test','POST',{})
        release=e.call('/releases/'+release['id']+'/submit','POST',{},release['rowVersion'])
        reviewer=Session('reviewer')
        release=reviewer.call('/releases/'+release['id']+'/approve','POST',{},release['rowVersion'])
        reviewer.call('/releases/'+release['id']+'/set-default','POST',{'reason':'启用开发 ERP 主动申请示例'},release['rowVersion'])
        print('ERP 玻璃布多表 Dataset、Mapping、Identity 和 GC 六位流水规则已完成双人发布。')
    with connect() as c:
        c.execute('''insert into erp_mdm_integration(code,tenant_code,dataset_code,record_table,key_env,candidate_status)
            values(%s,%s,%s,'cloth','ERP_DEMO_SOURCE_KEY',%s) on conflict(code) do nothing''',(CODE,TENANT,CODE,CODE))
    print('ERP 测试页面：前端入口 /erp/ （无需登录 MDM）；正式号由 MDM 生成。')

if __name__=='__main__':main()
