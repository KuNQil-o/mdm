"""Independent development ERP SoR. PostgreSQL persists source data, writeback receipts and downstream data."""
import argparse,decimal,json,os,re,sys,time,uuid
from pathlib import Path
from http.server import ThreadingHTTPServer,BaseHTTPRequestHandler
from urllib.parse import urlparse,unquote,parse_qs
sys.path.insert(0,'/workspace/.tools/python')
import psycopg
import workflow
from psycopg import sql
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb,set_json_loads
set_json_loads(lambda value:json.loads(value,parse_float=decimal.Decimal))
p=argparse.ArgumentParser();p.add_argument('--port',type=int,default=9092);a=p.parse_args()
def connection():return psycopg.connect(host='localhost',port=5432,dbname='mdm_erp_v3',user=os.environ.get('ERP_SIM_USER','mdm'),password=os.environ.get('ERP_SIM_PASSWORD','mdm_dev_only'),row_factory=dict_row)
def dumps(obj):
 def encode(v):
  if isinstance(v,decimal.Decimal):return '__DECIMAL__'+str(v)+'__END__'
  if hasattr(v,'isoformat'):return v.isoformat()
  return str(v)
 return re.sub(r'"__DECIMAL__(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)__END__"',r'\1',json.dumps(obj,default=encode,ensure_ascii=False))
with connection() as c:
 c.execute("CREATE TABLE IF NOT EXISTS erp_writeback_receipt(key text PRIMARY KEY,body_hash text NOT NULL,record_table text,record_key text,material_no text,state text,response jsonb,created_at timestamptz DEFAULT now())")
 c.execute("CREATE TABLE IF NOT EXISTS erp_scenario(target text PRIMARY KEY,mode text NOT NULL,remaining integer NOT NULL)")
allowed={'cloth','manufacturer','treatment','cloth_type','cloth_weight','width_ref','form_ref','grade_ref','duplicate_ref','flat_material'}
workflow.initialize(connection)
static=Path(__file__).parent/'static'
class Handler(BaseHTTPRequestHandler):
 def log_message(self,fmt,*args):print(fmt%args,flush=True)
 def respond(self,status,obj,headers=None):
  raw=dumps(obj).encode();self.send_response(status);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(raw)))
  for k,v in (headers or {}).items():self.send_header(k,v)
  self.end_headers();self.wfile.write(raw)
 def route(self):return [unquote(x) for x in urlparse(self.path).path.split('/') if x]
 def body(self):return json.loads(self.rfile.read(int(self.headers.get('Content-Length','0'))) or '{}',parse_float=decimal.Decimal)
 def table(self,name):
  if name not in allowed:raise ValueError('unregistered source table')
  return sql.Identifier(name)
 def read(self,c,table,key):return c.execute(sql.SQL('select * from {} where id=%s').format(self.table(table)),(key,)).fetchone()
 def external(self,row,document=False):
  if not row:return None
  if document:return {**row['data'],'category':row['category'],'id':row['id'],'materialNo':row['material_no'],'sourceVersion':row['version'],'sourceUpdatedAt':row['updated_at']}
  return row
 def do_GET(self):
  parts=self.route()
  if parts in (['erp'],['erp','app.js'],['erp','style.css']):
   file=static/('index.html' if parts==['erp'] else parts[1]);raw=file.read_bytes()
   self.send_response(200);self.send_header('Content-Type',{'html':'text/html; charset=utf-8','js':'text/javascript; charset=utf-8','css':'text/css; charset=utf-8'}[file.suffix[1:]]);self.send_header('Content-Length',str(len(raw)));self.end_headers();self.wfile.write(raw);return
  if parts[:2]==['erp','api']:
   try:
    with connection() as c:
     if parts==['erp','api','config']:
      configs=c.execute('select code,tenant_code,dataset_code,record_table from erp_mdm_integration where enabled order by code').fetchall()
      refs={name:c.execute(sql.SQL('select * from {} where active order by id').format(self.table(name))).fetchall() for name in ['manufacturer','cloth_type','cloth_weight','treatment','width_ref','form_ref','grade_ref']}
      return self.respond(200,{'integrations':configs,'references':refs})
     query=parse_qs(urlparse(self.path).query);cfg=workflow.integration(c,query.get('integration',['ERP_GLASS_DEMO'])[0])
     if parts==['erp','api','cloth']:
      rows=c.execute('''select c.*,r.state as request_state,r.error as request_error,r.result as assignment
          from cloth c left join erp_number_request r on r.source_record_key=c.id and r.integration_code=%s
          where c.status=%s order by c.updated_at desc limit 100''',(cfg['code'],cfg['candidate_status'])).fetchall()
      return self.respond(200,rows)
     if len(parts)==4 and parts[2]=='cloth':return self.respond(200,workflow.record(c,cfg,parts[3]))
    return self.respond(404,{'success':False,'code':'NOT_FOUND'})
   except workflow.WorkflowError as e:return self.respond(e.status,e.body)
  with connection() as c:
   if parts==['health']:return self.respond(200,{'status':'UP','kind':'ERP_V3_SOURCE_OF_RECORD'})
   if parts==['contract']:return self.respond(200,{'materialNoField':'materialNo','idempotency':True,'conditionalEmptyWrite':True,'queryCurrentValue':True})
   if parts and parts[0]=='source' and len(parts) in (2,3):
    table=parts[1]
    if len(parts)==3:return self.respond(200,self.external(self.read(c,table,parts[2])))
    rows=c.execute(sql.SQL('select * from {} order by id').format(self.table(table))).fetchall();return self.respond(200,rows)
   if parts and parts[0]=='documents':
    if len(parts)==2:return self.respond(200,self.external(self.read(c,'flat_material',parts[1]),True))
    rows=c.execute('select * from flat_material order by id').fetchall();return self.respond(200,[self.external(r,True) for r in rows])
   if len(parts)==3 and parts[0]=='writeback':
    record=self.read(c,parts[1],parts[2]);scope=parts[1]+':'+str((record or {}).get('category',''));scenario=c.execute('select * from erp_scenario where target in (%s,%s) order by (target=%s) desc limit 1',(scope,parts[1],scope)).fetchone()
    if scenario and scenario['mode']=='DELAY_QUERY' and scenario['remaining']!=0:return self.respond(503,{'success':False,'code':'QUERY_UNAVAILABLE'})
    row=self.read(c,parts[1],parts[2]);return self.respond(404,{'success':False,'code':'SOURCE_NOT_FOUND'}) if row is None else self.respond(200,{'success':True,'sourceRecordKey':row['id'],'materialNo':row['material_no'],'sourceVersion':row['version']})
   if parts and parts[0]=='downstream':
    if parts[1]=='items':rows=c.execute('select * from downstream_item').fetchall()
    elif parts[1]=='bom':rows=c.execute('select * from downstream_bom').fetchall()
    else:rows=c.execute('select * from downstream_routing').fetchall()
    return self.respond(200,rows)
   return self.respond(404,{'success':False,'code':'NOT_FOUND'})
 def do_POST(self):
  try:
   parts=self.route();body=self.body()
   if parts[:2]==['erp','api']:
    code=body.get('integration','ERP_GLASS_DEMO')
    if parts==['erp','api','cloth']:
     key=body.get('id') or str(uuid.uuid4())
     if not isinstance(key,str) or not re.fullmatch(r'[A-Za-z0-9_.-]{1,80}',key):raise workflow.WorkflowError(422,'SOURCE_KEY_INVALID','来源键须为 1–80 位字母、数字、下划线、点或连字符')
     values=body.get('fields',{})
     if not isinstance(values,dict):raise workflow.WorkflowError(422,'INVALID_SOURCE_FIELD','fields 必须为对象')
     result=workflow.perform(connection,dumps,code,key,values,body.get('assign',True))
     return self.respond(200,result)
    if len(parts)==5 and parts[2]=='cloth' and parts[4]=='request-number':
     return self.respond(200,workflow.perform(connection,dumps,code,parts[3]))
    return self.respond(404,{'success':False,'code':'NOT_FOUND'})
   with connection() as c:
    if len(parts)==2 and parts[0]=='scenarios':
     mode=body.get('mode','SUCCESS');assert mode in ['SUCCESS','BUSINESS_FAIL','SERVER_ERROR','RATE_LIMIT','LOST_RESPONSE','ACCEPTED','UNKNOWN','DELAY_QUERY']
     c.execute('insert into erp_scenario values(%s,%s,%s) on conflict(target) do update set mode=excluded.mode,remaining=excluded.remaining',(parts[1]+(':'+body['category'] if body.get('category') else ''),mode,body.get('remaining',1)));return self.respond(200,{'success':True})
    if parts==['contract','validate']:return self.respond(200,{'success':False,'code':'MATERIAL_NO_REQUIRED'})
    if len(parts)==2 and parts[0]=='records':
     table=parts[1];cols=list(body);values=[Jsonb(v,dumps=dumps) if isinstance(v,dict) else v for v in body.values()]
     c.execute(sql.SQL('insert into {} ({}) values ({})').format(self.table(table),sql.SQL(',').join(map(sql.Identifier,cols)),sql.SQL(',').join(sql.Placeholder() for _ in cols)),values);return self.respond(201,{'success':True,'id':body.get('id')})
    if parts==['documents']:
     key=body.get('id',str(uuid.uuid4()));c.execute('insert into flat_material(id,category,data) values(%s,%s,%s)',(key,body.get('category','GENERIC'),Jsonb(body['data'],dumps=dumps)));return self.respond(201,{'success':True,'id':key})
    if parts and parts[0]=='downstream':
     if parts[1]=='items':c.execute('insert into downstream_item values(%s,%s) on conflict(material_no) do update set data=excluded.data',(body['materialNo'],Jsonb(body.get('data',{}))))
     elif parts[1]=='bom':c.execute('insert into downstream_bom values(%s,%s,%s) on conflict(parent_no,child_no) do update set quantity=excluded.quantity',(body['parentNo'],body['childNo'],body['quantity']))
     elif parts[1]=='routing':c.execute('insert into downstream_routing values(%s,%s,%s)',(body['materialNo'],body['version'],Jsonb(body.get('data',{}))))
     else:return self.respond(404,{'success':False})
     return self.respond(200,{'success':True})
   return self.respond(404,{'success':False,'code':'NOT_FOUND'})
  except workflow.WorkflowError as e:return self.respond(e.status,e.body)
  except Exception:return self.respond(422,{'success':False,'code':'INVALID_SOURCE_RECORD','message':'ERP 记录无效，请检查输入；已保存记录可刷新列表查看'})
 def do_PATCH(self):
  parts=self.route();body=self.body()
  try:
   with connection() as c:
    if len(parts)==3 and parts[0]=='records':
     table=parts[1];columns=[k for k in body if k not in ('id','version')];values=[Jsonb(body[k],dumps=dumps) if isinstance(body[k],dict) else body[k] for k in columns]
     available={r['column_name'] for r in c.execute('select column_name from information_schema.columns where table_schema=\'public\' and table_name=%s',(table,)).fetchall()};setters=[sql.SQL('{}=%s').format(sql.Identifier(k)) for k in columns];setters+=([sql.SQL('version=version+1')] if 'version' in available else []);setters+=([sql.SQL('updated_at=now()')] if 'updated_at' in available else []);stmt=sql.SQL('update {} set {} where id=%s').format(self.table(table),sql.SQL(',').join(setters));c.execute(stmt,values+[parts[2]]);return self.respond(200,{'success':True})
    if len(parts)!=3 or parts[0]!='writeback':return self.respond(404,{'success':False})
    table,key=parts[1:];idem=self.headers.get('Idempotency-Key');no=body.get('materialNo')
    if not idem or not no:return self.respond(422,{'success':False,'code':'MATERIAL_NO_REQUIRED'})
    import hashlib
    digest=hashlib.sha256(dumps(body).encode()).hexdigest();c.execute('select pg_advisory_xact_lock(hashtextextended(%s,0))',(idem,));receipt=c.execute('select * from erp_writeback_receipt where key=%s',(idem,)).fetchone()
    if receipt:
     if receipt['body_hash']!=digest:return self.respond(409,{'success':False,'code':'IDEMPOTENCY_CONFLICT'})
     return self.respond(200,receipt['response'])
    record=self.read(c,table,key);scope=table+':'+str((record or {}).get('category',''));scenario=c.execute('select * from erp_scenario where target in (%s,%s) order by (target=%s) desc limit 1',(scope,table,scope)).fetchone();mode=scenario['mode'] if scenario and scenario['remaining']!=0 else 'SUCCESS'
    if scenario and scenario['remaining']>0:c.execute('update erp_scenario set remaining=remaining-1 where target=%s',(scenario['target'],));c.commit()
    if mode=='SERVER_ERROR':return self.respond(503,{'success':False,'code':'TEMPORARY_FAILURE'})
    if mode=='RATE_LIMIT':return self.respond(429,{'success':False,'code':'RATE_LIMIT'},{'Retry-After':'1'})
    if mode=='BUSINESS_FAIL':return self.respond(200,{'success':False,'code':'BUSINESS_REJECTED'})
    if mode=='UNKNOWN':self.close_connection=True;self.connection.shutdown(2);return
    row=c.execute(sql.SQL('select * from {} where id=%s for update').format(self.table(table)),(key,)).fetchone()
    if row is None:return self.respond(404,{'success':False,'code':'SOURCE_NOT_FOUND'})
    if row['material_no'] not in (None,'',no):return self.respond(409,{'success':False,'code':'ERP_VALUE_CONFLICT'})
    c.execute(sql.SQL('update {} set material_no=%s,version=version+1,updated_at=now() where id=%s').format(self.table(table)),(no,key))
    response={'success':True,'sourceRecordKey':key,'materialNo':no,'sourceVersion':row['version']+1}
    c.execute('insert into erp_writeback_receipt(key,body_hash,record_table,record_key,material_no,state,response) values(%s,%s,%s,%s,%s,%s,%s)',(idem,digest,table,key,no,'SUCCEEDED',Jsonb(response)));c.commit()
    if mode=='LOST_RESPONSE':self.close_connection=True;self.connection.shutdown(2);return
    return self.respond(202 if mode=='ACCEPTED' else 200,{**response,'success':mode!='ACCEPTED'})
  except Exception:return self.respond(422,{'success':False,'code':'WRITEBACK_REJECTED'})
print('Independent ERP V3 simulator port',a.port,flush=True)
ThreadingHTTPServer(('127.0.0.1',a.port),Handler).serve_forever()
