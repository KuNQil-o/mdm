import json,urllib.request,urllib.error,http.cookiejar,uuid
class Client:
 def __init__(self,user='editor',tenant='TENANT-A',base='http://localhost:8080'):
  self.base=base;self.tenant=tenant;self.cookies=http.cookiejar.CookieJar();self.opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookies));self.call('/api/dev/login','POST',{'userCode':user},prefix=False)
 def call(self,path,method='GET',body=None,version=None,key=None,prefix=True,expected=200):
  headers={'X-Tenant-Code':self.tenant,'X-Request-ID':str(uuid.uuid4())}
  if method!='GET':headers['Idempotency-Key']=key or str(uuid.uuid4())
  if version is not None:headers['If-Match']='"'+str(version)+'"'
  if body is not None:headers['Content-Type']='application/json'
  req=urllib.request.Request(self.base+('/api/v1' if prefix else '')+path,data=json.dumps(body).encode() if body is not None else None,headers=headers,method=method)
  try:r=self.opener.open(req,timeout=20);status=r.status;raw=r.read()
  except urllib.error.HTTPError as e:status=e.code;raw=e.read()
  try:data=json.loads(raw)
  except Exception:data=raw
  assert status==expected,(method,path,status,data)
  return data
