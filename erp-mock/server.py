"""独立开发ERP模拟器。SQLite持久化；不代表任何真实ERP接口。"""
import json, sqlite3, threading, time, uuid, argparse
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, unquote
from pathlib import Path
parser=argparse.ArgumentParser();parser.add_argument('--port',type=int,default=9090);parser.add_argument('--database',default='.runtime/erp.sqlite');args=parser.parse_args()
Path(args.database).parent.mkdir(parents=True,exist_ok=True)
lock=threading.RLock()
conn=sqlite3.connect(args.database,check_same_thread=False)
conn.row_factory=sqlite3.Row
conn.executescript('''CREATE TABLE IF NOT EXISTS material(target TEXT,tenant TEXT,id TEXT,external_id TEXT UNIQUE,version INTEGER,data TEXT,PRIMARY KEY(target,tenant,id));
CREATE TABLE IF NOT EXISTS receipt(target TEXT,event_id TEXT,body_hash TEXT,envelope TEXT,status TEXT,external_id TEXT,polls INTEGER DEFAULT 0,PRIMARY KEY(target,event_id));
CREATE TABLE IF NOT EXISTS scenario(target TEXT PRIMARY KEY,mode TEXT,remaining INTEGER);
''');conn.commit()
class Handler(BaseHTTPRequestHandler):
 def log_message(self,fmt,*a): print(fmt%a,flush=True)
 def respond(self,status,obj):
  payload=json.dumps(obj,ensure_ascii=False).encode();self.send_response(status);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(payload)));self.end_headers();self.wfile.write(payload)
 def route(self):return [unquote(x) for x in urlparse(self.path).path.split('/') if x]
 def body(self):return json.loads(self.rfile.read(int(self.headers.get('Content-Length','0'))) or '{}')
 def do_GET(self):
  with lock:
   p=self.route()
   if p==['health']:return self.respond(200,{'status':'UP','kind':'LOCAL_ERP_SIMULATOR'})
   if p==['scenarios']:return self.respond(200,[dict(r) for r in conn.execute('select * from scenario')])
   if len(p)>=3 and p[0]=='targets':
    target=p[1]
    if p[2]=='events' and len(p)==4:
     r=conn.execute('select * from receipt where target=? and event_id=?',(target,p[3])).fetchone()
     if not r:return self.respond(404,{'success':False,'code':'NOT_FOUND'})
     if r['status']=='ACCEPTED':
      if r['polls']==0:
       conn.execute('update receipt set polls=polls+1 where target=? and event_id=?',(target,p[3]));conn.commit();return self.respond(202,{'success':False,'status':'ACCEPTED','externalId':r['external_id']})
      self.persist(target,json.loads(r['envelope']),r['external_id']);conn.execute('update receipt set status=\'SUCCEEDED\' where target=? and event_id=?',(target,p[3]));conn.commit()
     envelope=json.loads(r['envelope']);return self.respond(200,{'success':True,'externalId':r['external_id'],'rowVersion':envelope['rowVersion']})
    if p[2]=='materials':
     if len(p)==3:return self.respond(200,[self.external(r) for r in conn.execute('select * from material where target=?',(target,))])
     r=conn.execute('select * from material where target=? and external_id=?',(target,p[3])).fetchone()
     return self.respond(200,self.external(r)) if r else self.respond(404,{'success':False,'code':'NOT_FOUND'})
   return self.respond(404,{'success':False,'code':'NOT_FOUND'})
 def external(self,r):return {'success':True,'externalId':r['external_id'],'materialId':r['id'],'tenantId':r['tenant'],'rowVersion':r['version'],'data':json.loads(r['data'])}
 def persist(self,target,envelope,external):
  conn.execute('insert into material(target,tenant,id,external_id,version,data) values(?,?,?,?,?,?) on conflict(target,tenant,id) do update set version=excluded.version,data=excluded.data',(target,envelope['tenantId'],envelope['materialId'],external,envelope['rowVersion'],json.dumps(envelope['data'],ensure_ascii=False,sort_keys=True)))
 def do_POST(self):
  with lock:
   try:b=self.body()
   except Exception:return self.respond(400,{'success':False,'code':'BAD_JSON'})
   p=self.route()
   if len(p)==2 and p[0]=='scenarios':
    mode=b.get('mode','SUCCESS');allowed=['SUCCESS','BUSINESS_FAIL','RATE_LIMIT','SERVER_ERROR','LOST_RESPONSE','ACCEPTED','UNKNOWN']
    if mode not in allowed:return self.respond(422,{'success':False,'allowed':allowed})
    conn.execute('insert into scenario values(?,?,?) on conflict(target) do update set mode=excluded.mode,remaining=excluded.remaining',(p[1],mode,b.get('remaining',-1)));conn.commit();return self.respond(200,{'mode':mode,'remaining':b.get('remaining',-1)})
   if len(p)==3 and p[0]=='targets' and p[2]=='validate':return self.respond(200,{'success':False,'code':'ITEM_REQUIRED','message':'故意无效的业务样本'})
   if len(p)!=3 or p[0]!='targets' or p[2]!='materials':return self.respond(404,{'success':False})
   target=p[1]
   if any(k not in b for k in ['eventId','tenantId','materialId','rowVersion','data']):return self.respond(422,{'success':False,'code':'ENVELOPE_REQUIRED'})
   import hashlib
   h=hashlib.sha256(json.dumps(b,sort_keys=True,separators=(',',':')).encode()).hexdigest()
   old=conn.execute('select * from receipt where target=? and event_id=?',(target,b['eventId'])).fetchone()
   if old:
    if old['body_hash']!=h:return self.respond(409,{'success':False,'code':'IDEMPOTENCY_BODY_CONFLICT'})
    return self.respond(202 if old['status']=='ACCEPTED' else 200,{'success':old['status']=='SUCCEEDED','externalId':old['external_id'],'duplicate':True,'rowVersion':json.loads(old['envelope'])['rowVersion']})
   scenario=conn.execute('select * from scenario where target=?',(target,)).fetchone();mode=scenario['mode'] if scenario and scenario['remaining']!=0 else 'SUCCESS'
   if scenario and scenario['remaining']>0:conn.execute('update scenario set remaining=remaining-1 where target=?',(target,));conn.commit()
   if mode=='BUSINESS_FAIL':return self.respond(200,{'success':False,'code':'BUSINESS_REJECTED','message':'目标业务校验失败'})
   if mode=='RATE_LIMIT':return self.respond(429,{'success':False,'code':'RATE_LIMIT'})
   if mode=='SERVER_ERROR':return self.respond(503,{'success':False,'code':'TEMPORARY_FAILURE'})
   if mode=='UNKNOWN':self.close_connection=True;self.connection.shutdown(2);return
   existing=conn.execute('select * from material where target=? and tenant=? and id=?',(target,b['tenantId'],b['materialId'])).fetchone()
   if existing and b['rowVersion']<=existing['version']:return self.respond(409,{'success':False,'code':'OLD_VERSION'})
   if b.get('previousPublishedVersion') is not None and (not existing or existing['version']!=b['previousPublishedVersion']):return self.respond(409,{'success':False,'code':'PREDECESSOR_NOT_CONFIRMED'})
   external=existing['external_id'] if existing else target.upper()+'-'+str(uuid.uuid4())
   status='ACCEPTED' if mode=='ACCEPTED' else 'SUCCEEDED'
   conn.execute('insert into receipt(target,event_id,body_hash,envelope,status,external_id) values(?,?,?,?,?,?)',(target,b['eventId'],h,json.dumps(b,ensure_ascii=False),status,external))
   if status=='SUCCEEDED':self.persist(target,b,external)
   conn.commit()
   if mode=='LOST_RESPONSE':self.close_connection=True;self.connection.shutdown(2);return
   return self.respond(202 if status=='ACCEPTED' else 200,{'success':status=='SUCCEEDED','externalId':external,'rowVersion':b['rowVersion']})
print(f'ERP simulator listening on port {args.port}; persistent database {args.database}',flush=True)
ThreadingHTTPServer(('127.0.0.1',args.port),Handler).serve_forever()
