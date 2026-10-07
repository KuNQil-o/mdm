import { reactive } from 'vue';
export const session=reactive({tenant:'',user:null as any,permissions:[] as string[],error:'',notice:'',busy:0,generation:0});
const pending=new Map<string,string>();
export async function api(path:string,method='GET',body?:any,version?:number,callerKey?:string):Promise<any>{
 const generation=session.generation;
 const headers:Record<string,string>={'X-Tenant-Code':session.tenant,'X-Request-ID':crypto.randomUUID()};
 if(body!==undefined) headers['Content-Type']='application/json';
 if(version!==undefined) headers['If-Match']='"'+version+'"';
 if(callerKey){
  headers['X-Caller-Key']=callerKey;
  headers['X-Timestamp']=String(Math.floor(Date.now()/1000));headers['X-Nonce']=crypto.randomUUID();
  const bytes=new TextEncoder().encode(body===undefined?'':JSON.stringify(body));
  const hex=(buffer:ArrayBuffer)=>Array.from(new Uint8Array(buffer),x=>x.toString(16).padStart(2,'0')).join('');
  const hash=hex(await crypto.subtle.digest('SHA-256',bytes));
  const message=[headers['X-Timestamp'],headers['X-Nonce'],method,'/api/v1/'+path,hash].join('\n');
  const key=await crypto.subtle.importKey('raw',new TextEncoder().encode(callerKey),{name:'HMAC',hash:'SHA-256'},false,['sign']);
  headers['X-Signature']=hex(await crypto.subtle.sign('HMAC',key,new TextEncoder().encode(message)));
 }
 const signature=session.tenant+'|'+method+'|'+path+'|'+JSON.stringify(body);
 if(method!=='GET') headers['Idempotency-Key']=pending.get(signature)||crypto.randomUUID();
 const abort=new AbortController(); const timer=setTimeout(()=>abort.abort(),30000);
 session.busy++; session.error='';
 try{
  const r=await fetch('/api/v1/'+path,{method,headers,body:body===undefined?undefined:JSON.stringify(body),signal:abort.signal,cache:'no-store'});
  const data=await r.json();
  if(generation!==session.generation) throw new Error('CONTEXT_CHANGED');
  if(!r.ok){
   if(r.status<500)pending.delete(signature);
   else pending.set(signature,headers['Idempotency-Key']);
   throw new Error(data.code+'：'+data.message+(data.details?.length?'\n'+data.details.map((e:any)=>e.fieldPath+' '+e.message).join('\n'):'')+'\n请求ID：'+data.requestId);
  }
  pending.delete(signature); return data;
 }catch(e:any){
  if(e.message==='CONTEXT_CHANGED') throw e;
  if(e instanceof TypeError||e.name==='AbortError'){
   pending.set(signature,headers['Idempotency-Key']);session.error='请求结果待确认。请保持相同输入重试，系统将使用原幂等键。';
  }else session.error=e.message;
  throw e;
 }finally{clearTimeout(timer);session.busy--;}
}
export const pretty=(value:any)=>JSON.stringify(value,null,2);
export const labels:Record<string,string>={DRAFT:'草稿',ACTIVE:'已启用',SUSPENDED:'已暂停',REVIEW:'待审批',PUBLISHED:'已发布',RETIRED:'已退役',ARCHIVED:'已归档',ISSUED:'已发号',REUSED:'已复用'};
