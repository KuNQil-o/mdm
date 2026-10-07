<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { api, session, pretty, labels } from './api';
const tab=ref('overview'), userCode=ref('editor'),password=ref(''), devMode=ref(true),tenants=ref<any[]>([]),
 callers=ref<any[]>([]),categories=ref<any[]>([]),configs=ref<any[]>([]),releases=ref<any[]>([]),ledger=ref<any>({items:[],total:0}),
 monitor=ref<any>(null),audits=ref<any[]>([]),members=ref<any[]>([]),roles=ref<any[]>([]),users=ref<any[]>([]),tenantRows=ref<any[]>([]),
 selected=ref<any>(null),result=ref<any>(null),resource=ref('schemas'),editor=ref(''),configCode=ref(''),categoryCode=ref('GLASS_CLOTH'),
 callerKey=ref(''),revealedKey=ref(''),callerCode=ref('ERP_DEMO'),sourceKey=ref('100001'),sample=ref(''),releaseId=ref(''),
 searchNo=ref(''),searchCategory=ref(''),searchCaller=ref(''),searchSource=ref(''),searchHash=ref(''),searchFrom=ref(''),searchTo=ref(''),offset=ref(0),
 name=ref(''), newCode=ref(''),callerType=ref('ERP'),callerEnvironment=ref('DEV'),callerScope=ref<string[]>([]),
 releaseCode=ref(''),releaseRefs=ref(''),releaseSamples=ref(''),tenantName=ref(''),tenantCode=ref(''),
 memberUser=ref(''),memberRoles=ref<string[]>([]),memberScope=ref('*'),roleCode=ref(''),rolePermissions=ref(''),
 createUserCode=ref(''),createUserName=ref(''),createUserPassword=ref('');
const resources=[['schemas','Schema / 属性'],['input-profiles','Input Profile'],['validation-rules','Validation / 校验'],
 ['derivation-rules','Derivation / 派生'],['identity-definitions','Material Identity'],['code-rules','Code Rule / 编码段'],['reference-data','字典与引用']];
const nav=[['overview','01','工作台','MONITOR_READ'],['callers','02','调用系统','CALLER_MANAGE'],['categories','03','类别与 Schema','SCHEMA_DESIGN'],
 ['configuration','04','身份与编码规则','PREVIEW'],['preview','05','发号预览','PREVIEW'],['releases','06','发布与审批','PREVIEW'],
 ['ledger','07','发号账本','ASSIGNMENT_READ'],['audit','08','审计日志','AUDIT_READ'],['monitor','09','运行监控','MONITOR_READ'],['access','10','租户与权限','TENANT_ADMIN']];
const can=(p:string)=>session.permissions.includes(p);
const visibleNav=computed(()=>nav.filter(n=>can(n[3])));
const title=computed(()=>nav.find(n=>n[0]===tab.value)?.[2]||'工作台');
const uniqueCategories=computed(()=>{const m=new Map<string,any>();for(const c of categories.value)if(!m.has(c.categoryCode))m.set(c.categoryCode,c);return [...m.values()];});
const selectedRelease=computed(()=>releases.value.find(r=>r.categoryCode===categoryCode.value&&r.status==='PUBLISHED'));
const schemaFields=computed(()=>selectedRelease.value?.dependencySnapshot?.schema?.definition?.attributes||[]);
async function safe(work:()=>Promise<any>){try{return await work();}catch{}}
function reset(){session.generation++;selected.value=null;result.value=null;callerKey.value='';revealedKey.value='';offset.value=0;editor.value='';configs.value=[];}
async function login(){
 await api(devMode.value?'dev/login':'auth/login','POST',{userCode:userCode.value,...(devMode.value?{}:{password:password.value})});
 session.user=await api('me');tenants.value=await api('me/tenants');session.tenant=tenants.value[0]?.code||'';await changeTenant();
}
async function logout(){await api('auth/logout','POST',{});reset();session.user=null;session.tenant='';session.permissions=[];}
async function changeTenant(){reset();await identity();if(!visibleNav.value.some(n=>n[0]===tab.value))tab.value=visibleNav.value[0]?.[0]||'ledger';await load();}
async function identity(){
 const data=await api('context');session.permissions=data.permissions;
 const [cats,rels]=await Promise.all([api('categories'),api('release-packages')]);categories.value=cats;releases.value=rels;
 if(!uniqueCategories.value.some(c=>c.categoryCode===categoryCode.value))categoryCode.value=uniqueCategories.value[0]?.categoryCode||'';
}
async function load(){
 selected.value=null;result.value=null;revealedKey.value='';
 if(!session.user)return;
 if(tab.value==='overview'||tab.value==='monitor')monitor.value=await api('monitoring');
 if(tab.value==='callers')callers.value=await api('caller-systems');
 if(tab.value==='categories'||tab.value==='releases'||tab.value==='preview')await identity();
 if(tab.value==='configuration'){await identity();configs.value=await api(resource.value);}
 if(tab.value==='ledger')await search();
 if(tab.value==='audit')audits.value=await api('audit-logs');
 if(tab.value==='access'){
  [members.value,roles.value]=await Promise.all([api('members'),api('roles')]);
  if(session.user.platformAdmin)[users.value,tenantRows.value]=await Promise.all([api('users'),api('tenants')]);
 }
 if(tab.value==='preview')fillSample();
}
function fillSample(){const r=releaseId.value?releases.value.find(r=>r.id===releaseId.value):selectedRelease.value;
 const first=r?.samples?.find((s:any)=>!s.expectedError)||r?.samples?.[0];sample.value=pretty(first?.attributes||{});callerCode.value=first?.callerSystemCode||'ERP_DEMO';result.value=null;}
async function createCategory(){await api('categories','POST',{code:newCode.value,definition:{name:name.value,identityReusePolicy:'REUSE_EXISTING',numberingPolicy:'NUMBER_ONCE'}});newCode.value='';name.value='';await identity();}
async function createCaller(){const c=await api('caller-systems','POST',{code:newCode.value,name:name.value,systemType:callerType.value,environment:callerEnvironment.value,status:'ACTIVE',allowedCategoryCodes:callerScope.value});
 callers.value=await api('caller-systems');revealedKey.value=c.apiKey;selected.value=c;newCode.value='';}
async function callerStatus(c:any,status:string){await api('caller-systems/'+c.id,'PATCH',{status},c.rowVersion);await load();}
async function rotate(c:any){const updated=await api('caller-systems/'+c.id+'/rotate-key','POST',{},c.rowVersion);callers.value=await api('caller-systems');revealedKey.value=updated.apiKey;selected.value=updated;}
function chooseCaller(c:any){selected.value=c;editor.value=pretty({name:c.name,systemType:c.systemType,environment:c.environment,status:c.status,allowedCategoryCodes:c.allowedCategoryCodes,owner:c.owner,description:c.description,rateLimit:c.rateLimit});revealedKey.value='';}
async function updateCaller(){const updated=await api('caller-systems/'+selected.value.id,'PATCH',JSON.parse(editor.value),selected.value.rowVersion);callers.value=await api('caller-systems');chooseCaller(updated);session.notice='调用系统已更新';}
const examples:Record<string,any>={
 schemas:{attributes:[{attributeCode:'name',name:'名称',type:'STRING',required:true,case:'UPPER',length:80}]},
 'input-profiles':{callerSystemCode:'ERP_DEMO',fields:[{source:'vendor',target:'manufacturer',trim:true,case:'UPPER'}]},
 'validation-rules':{rules:[{field:'width',severity:'ERROR',assert:{op:'gte',args:[{field:'width'},100]},message:'幅宽至少100'}]},
 'derivation-rules':{attributes:[{field:'sizeClass',expression:{op:'if',args:[{op:'gte',args:[{field:'width'},1000]},'WIDE','NORMAL']}}]},
 'identity-definitions':{attributes:[{field:'manufacturer',trim:true,case:'UPPER'},{field:'width',scale:3}]},
 'code-rules':{separator:'-',maxLength:128,segments:[{type:'CONSTANT',value:'MT'},{type:'SEQUENCE',name:'MAIN',width:6,reset:'NEVER'}]},
 'reference-data':{entries:[{code:'VENDOR_A',name:'供应商 A',aliases:['A'],numberCode:'VA',active:true}]}};
function newConfig(){selected.value=null;editor.value=pretty(examples[resource.value]);configCode.value='';}
function chooseConfig(c:any){selected.value=c;configCode.value=c.code;categoryCode.value=c.categoryCode;editor.value=pretty(c.definition);}
async function saveConfig(asNew=false){
 const definition=JSON.parse(editor.value);
 if(selected.value&&!asNew){await api(resource.value+'/'+selected.value.id,'PATCH',{definition},selected.value.rowVersion);}
 else await api(resource.value,'POST',{code:configCode.value,categoryCode:categoryCode.value,definition});
 await load();session.notice='配置已保存';
}
async function saveCategoryVersion(c:any){await api('categories','POST',{code:c.code,definition:JSON.parse(editor.value)});await identity();selected.value=null;}
async function categoryAvailability(c:any){await api('categories/'+c.id+'/'+(c.categoryEnabled?'suspend':'activate'),'POST',{},c.availabilityVersion);await identity();}
async function preview(){result.value=await api('material-number-previews','POST',{callerSystemCode:callerCode.value,categoryCode:categoryCode.value,attributes:(JSON.parse as any)(sample.value,(_key:string,value:any,context:any)=>typeof value==='number'&&context?.source?context.source:value),...(releaseId.value?{releasePackageVersionId:releaseId.value}:{})});}
async function issue(){
 if(!callerKey.value){session.error='请输入当前调用系统的 API Key';return;}
 result.value=await api('material-number-assignments','POST',{callerSystemCode:callerCode.value,categoryCode:categoryCode.value,sourceRecordKey:sourceKey.value,attributes:(JSON.parse as any)(sample.value,(_key:string,value:any,context:any)=>typeof value==='number'&&context?.source?context.source:value)},undefined,callerKey.value);
}
async function newRelease(){
 const cat=uniqueCategories.value.find(c=>c.categoryCode===categoryCode.value);
 const refs:any={categoryVersionId:cat?.id};
 for(const [res,key] of [['schemas','schemaVersionId'],['identity-definitions','identityDefinitionVersionId'],['code-rules','codeRuleVersionId']]){
  const list=await api(res);refs[key]=list.find((x:any)=>x.categoryCode===categoryCode.value&&x.status!=='RETIRED')?.id||'';
 }
 for(const [res,key] of [['validation-rules','validationRuleVersionIds'],['derivation-rules','derivationRuleVersionIds'],['input-profiles','inputProfileVersionIds'],['reference-data','dictionaryVersionIds']]){
  refs[key]=(await api(res)).filter((x:any)=>x.categoryCode===categoryCode.value&&x.status!=='RETIRED').slice(0,1).map((x:any)=>x.id);
 }
 selected.value=null;releaseCode.value=categoryCode.value+'_RELEASE';releaseRefs.value=pretty(refs);releaseSamples.value=pretty(selectedRelease.value?.samples||[{callerSystemCode:'ERP_DEMO',attributes:{}}]);
}
function chooseRelease(r:any){selected.value=r;releaseCode.value=r.code;categoryCode.value=r.categoryCode;releaseRefs.value=pretty(r.refs);releaseSamples.value=pretty(r.samples);result.value=r.tests;}
async function saveRelease(){const body={refs:JSON.parse(releaseRefs.value),samples:JSON.parse(releaseSamples.value)};
 const r=selected.value?.status==='DRAFT'?await api('release-packages/'+selected.value.id,'PATCH',body,selected.value.rowVersion):
 await api('release-packages','POST',{...body,code:releaseCode.value,categoryCode:categoryCode.value});await identity();chooseRelease(r);}
async function releaseAction(r:any,action:string){const updated=await api('release-packages/'+r.id+'/'+action,'POST',{},r.rowVersion);await identity();chooseRelease(updated);}
async function search(){const q=new URLSearchParams({offset:String(offset.value),limit:'25'});
 for(const [key,value] of [['materialNo',searchNo.value],['categoryCode',searchCategory.value],['callerSystemCode',searchCaller.value],['sourceRecordKey',searchSource.value],['identityHash',searchHash.value],['from',searchFrom.value],['to',searchTo.value]])if(value)q.set(key,key==='from'||key==='to'?new Date(value).toISOString():value);
 ledger.value=await api('material-number-assignments?'+q.toString());}
async function explain(id:string){result.value=await api('material-number-assignments/'+id+'/explain');selected.value={id};}
async function createTenant(){await api('tenants','POST',{code:tenantCode.value,name:tenantName.value});tenants.value=await api('me/tenants');await load();}
async function tenantStatus(t:any,status:string){await api('tenants/'+t.id,'PATCH',{status},t.rowVersion);await load();}
function chooseMember(m:any){selected.value=m;memberUser.value=m.userId;memberRoles.value=m.roles;memberScope.value=m.categoryScope.join(',');}
async function saveMember(){const body={userId:memberUser.value,roles:memberRoles.value,categoryScope:memberScope.value.split(',').map(v=>v.trim()).filter(Boolean),active:true};
 await api(selected.value?'members/'+selected.value.userId:'members',selected.value?'PATCH':'POST',body,selected.value?.rowVersion);await load();}
async function disableMember(m:any){await api('members/'+m.userId,'PATCH',{active:!m.active},m.rowVersion);await load();}
async function createRole(){await api('roles','POST',{code:roleCode.value,permissions:JSON.parse(rolePermissions.value)});await load();}
async function createUser(){await api('users','POST',{code:createUserCode.value,name:createUserName.value,password:createUserPassword.value});createUserPassword.value='';await load();}
watch(tab,()=>safe(load));
watch(resource,()=>safe(async()=>{configs.value=await api(resource.value);newConfig();}));
onMounted(()=>safe(async()=>{
 try{await api('dev/users');}catch{devMode.value=false;session.error='';}
 try{session.user=await api('me');tenants.value=await api('me/tenants');session.tenant=tenants.value[0]?.code||'';await changeTenant();}catch{session.error='';}
}));
</script>

<template>
 <div v-if="!session.user" class="login-layout">
  <div class="login-story"><div class="brand"><span class="brand-mark">M</span><span>物料身份与料号治理<span class="brand-version">V4.0</span></span></div>
   <div><p class="eyebrow">MATERIAL IDENTITY & NUMBER GOVERNANCE</p><h1>每一种物料，<br>一个可信的身份。</h1><p>连接业务系统与统一编码规则，让每一次发号<br>唯一、可靠，并有据可循。</p><div class="pipeline">标准属性 <span>→</span> 物料身份 <span>→</span> 企业料号</div></div>
   <small>API FIRST · METADATA DRIVEN · NUMBER ONCE</small></div>
  <form class="login-card" @submit.prevent="safe(login)"><p class="eyebrow">治理工作台</p><h2>登录平台</h2><p class="muted">选择身份，进入您的企业工作空间。</p>
   <label>用户<select v-if="devMode" v-model="userCode"><option value="editor">规则设计员 · editor</option><option value="reviewer">规则审批员 · reviewer</option><option value="admin">平台管理员 · admin</option><option value="reader">业务查询员 · reader</option><option value="auditor">审计员 · auditor</option></select><input v-else v-model="userCode" autocomplete="username" required></label>
   <label v-if="!devMode">密码<input v-model="password" type="password" autocomplete="current-password" required></label>
   <button class="primary" :disabled="!!session.busy">进入工作台 <span>→</span></button><p v-if="devMode" class="help">开发演示环境 · 生产环境使用账号密码登录</p><pre v-if="session.error" class="error">{{ session.error }}</pre>
  </form>
 </div>
 <div v-else class="app-layout">
  <aside class="sidebar"><div class="brand"><span class="brand-mark">M</span><span>物料身份<br><small>与料号治理平台</small></span></div><div class="workspace-tag"><span class="status-dot"></span>企业工作空间 <b>V4.0</b></div>
   <nav><button v-for="n in visibleNav" :key="n[0]" :class="{active:tab===n[0]}" @click="tab=n[0]"><span>{{ n[1] }}</span>{{ n[2] }}<i v-if="tab===n[0]">●</i></button></nav>
   <div class="sidebar-bottom"><span class="status-dot"></span> API First <p>统一身份 · 唯一发号 · 历史可解释</p></div>
  </aside>
  <div class="main-layout"><header class="topbar"><span>治理平台 <span class="slash">/</span> {{ title }}</span><div class="topbar-actions"><select aria-label="当前租户" v-model="session.tenant" @change="safe(changeTenant)"><option v-for="t in tenants" :key="t.id" :value="t.code">{{ t.name }} · {{ t.code }}</option></select><span class="avatar">{{ session.user.name.slice(0,1) }}</span><span>{{ session.user.name }}</span><button class="text-button" @click="safe(logout)">退出</button></div></header>
   <main><div class="page-heading"><div><p class="eyebrow">{{ tab==='overview'?'YOUR GOVERNANCE WORKSPACE':'MATERIAL NUMBER GOVERNANCE' }}</p><h1>{{ title }}<span v-if="tab==='overview'" class="tag">API First</span></h1><p class="muted">{{ tab==='overview'?'从标准输入到唯一料号，查看企业身份治理的运行概况。':tab==='preview'?'先验证输入与规则，再由调用系统正式申请。预览不占用流水。':tab==='ledger'?'查询永久发号事实，追溯每一个号码的完整决策依据。':'配置与身份规则共同进入发布包，审核通过后用于正式发号。' }}</p></div><button :disabled="!!session.busy" @click="safe(load)">↻ 刷新</button></div>
   <div v-if="session.error" role="alert" class="error alert"><pre>{{ session.error }}</pre><button @click="session.error=''">关闭</button></div><div v-if="session.notice" class="notice" @click="session.notice=''">{{ session.notice }} <span>×</span></div><div v-if="session.busy" class="loading-line"></div>

   <template v-if="tab==='overview' && monitor">
    <div class="stat-grid"><article><p>24 小时调用量</p><strong>{{ monitor.summary.requests.toLocaleString() }}</strong><small>同步发号与预览请求</small></article><article><p>请求成功率</p><strong>{{ monitor.summary.requests?((monitor.summary.successes/monitor.summary.requests)*100).toFixed(1):'—' }}<em v-if="monitor.summary.requests">%</em></strong><small>成功 {{ monitor.summary.successes }} 次</small></article><article><p>发号账本</p><strong>{{ monitor.recentAssignments.total.toLocaleString() }}</strong><small>永久分配的企业料号</small></article><article><p>API P95 延迟</p><strong>{{ Number(monitor.summary.p95Ms).toFixed(1) }}<em>ms</em></strong><small>最近 24 小时响应分布</small></article></div>
    <section class="flow-banner"><div><p class="eyebrow">ONE MATERIAL. ONE IDENTITY.</p><h2>调用方准备事实，平台判定身份与唯一发号。</h2><p>所有类别共用一条配置驱动的发号管线。</p></div><div class="flow-steps"><span>Schema</span><i>→</i><span>Identity</span><i>→</i><span class="flow-final">materialNo</span></div></section>
    <div class="two-column"><section class="panel"><div class="panel-title"><h2>最近发号</h2><button class="text-button" @click="tab='ledger'">查看账本 →</button></div><table><thead><tr><th>企业料号</th><th>类别</th><th>来源绑定</th><th>发号时间</th></tr></thead><tbody><tr v-for="a in monitor.recentAssignments.items" :key="a.assignmentId" @click="tab='ledger';safe(()=>explain(a.assignmentId))"><td class="mono accent">{{ a.materialNo }}</td><td>{{ a.categoryCode }}</td><td>{{ a.sourceBindingCount }}</td><td>{{ new Date(a.issuedAt).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai'}) }}</td></tr></tbody></table><div v-if="!monitor.recentAssignments.items.length" class="empty">尚无正式发号记录。调用系统通过 API 申请后显示在这里。</div></section>
     <section class="panel"><div class="panel-title"><h2>运行状态</h2><span class="badge green">正常连接</span></div><div class="health-row"><span>数据库</span><b>{{ monitor.database.healthy?'连接正常':'异常' }}</b></div><div class="health-row"><span>失败请求</span><b>{{ monitor.summary.failures }}</b></div><div class="health-row"><span>冲突请求</span><b>{{ monitor.summary.conflicts }}</b></div><div class="health-row"><span>当前发布类别</span><b>{{ releases.filter(r=>r.status==='PUBLISHED').length }}</b></div><button class="full-width" @click="tab='preview'">打开发号预览 →</button></section></div>
    <section class="panel"><div class="panel-title"><h2>已配置的物料类别</h2><span class="muted">同一核心 · 不同行业</span></div><div class="category-cards"><article v-for="c in uniqueCategories" :key="c.id"><span class="category-symbol">{{ c.definition.name?.slice(0,1)||'物' }}</span><h3>{{ c.definition.name||c.code }}</h3><code>{{ c.code }}</code><p>Identity 复用 · {{ c.definition.identityReusePolicy==='REVIEW_ON_DUPLICATE'?'人工确认':'自动复用' }}</p><button class="text-button" @click="categoryCode=c.code;tab='preview'">检查规则与预览 →</button></article></div></section>
   </template>

   <template v-if="tab==='callers'">
    <section class="panel"><div class="panel-title"><h2>注册调用系统</h2><span class="muted">API Key 仅在创建和轮换时显示</span></div><form class="form-grid" @submit.prevent="safe(createCaller)"><label>系统代码<input v-model="newCode" placeholder="ERP_PROD" required></label><label>系统名称<input v-model="name" placeholder="生产 ERP" required></label><label>类型<select v-model="callerType"><option v-for="v in ['ERP','PLM','MES','CUSTOM','OTHER']" :key="v">{{ v }}</option></select></label><label>环境<select v-model="callerEnvironment"><option v-for="v in ['DEV','TEST','PROD']" :key="v">{{ v }}</option></select></label><label class="span-2">允许类别<select multiple v-model="callerScope"><option v-for="c in uniqueCategories" :key="c.id" :value="c.code">{{ c.definition.name }} · {{ c.code }}</option></select></label><button class="primary" :disabled="!!session.busy">注册并生成凭据</button></form></section>
    <div v-if="revealedKey" class="key-reveal"><strong>请立即保存新 API Key</strong><p>关闭页面后无法再次读取。轮换会使旧凭据立即失效。</p><code>{{ revealedKey }}</code></div>
    <section class="panel"><table><thead><tr><th>调用系统</th><th>类型 / 环境</th><th>允许类别</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="c in callers" :key="c.id"><td><b>{{ c.name }}</b><small class="block mono">{{ c.code }}</small></td><td>{{ c.systemType }} / {{ c.environment }}</td><td>{{ c.allowedCategoryCodes.join('、')||'未授权类别' }}</td><td><span :class="['badge',c.status==='ACTIVE'?'green':'']">{{ labels[c.status] }}</span></td><td class="actions"><button @click="chooseCaller(c)">编辑</button><button v-if="c.status!=='RETIRED'" @click="safe(()=>callerStatus(c,c.status==='ACTIVE'?'SUSPENDED':'ACTIVE'))">{{ c.status==='ACTIVE'?'暂停':'启用' }}</button><button v-if="c.status!=='RETIRED'" @click="safe(()=>rotate(c))">轮换凭据</button><button v-if="c.status!=='RETIRED'" @click="safe(()=>callerStatus(c,'RETIRED'))">退役</button></td></tr></tbody></table></section>
    <section v-if="selected" class="panel"><h2>调用系统配置 · {{ selected.code }}</h2><p class="help">调整允许类别、状态、负责人、描述和每分钟限流。</p><textarea v-model="editor" class="code-editor" rows="12" aria-label="调用系统配置"></textarea><button class="primary" :disabled="!!session.busy||selected.status==='RETIRED'" @click="safe(updateCaller)">保存修改</button></section>
   </template>

   <template v-if="tab==='categories'">
    <section class="panel"><form class="inline-form" @submit.prevent="safe(createCategory)"><label>类别代码<input v-model="newCode" placeholder="CATEGORY_CODE" required></label><label>类别名称<input v-model="name" placeholder="物料类别名称" required></label><button class="primary" :disabled="!!session.busy">创建类别草稿</button></form></section>
    <section class="panel"><table><thead><tr><th>物料类别</th><th>版本</th><th>复用策略</th><th>发布状态</th><th>操作</th></tr></thead><tbody><tr v-for="c in categories" :key="c.id"><td><b>{{ c.definition.name||c.code }}</b><small class="block mono">{{ c.code }}</small></td><td>V{{ c.version }}</td><td>{{ c.definition.identityReusePolicy }}</td><td><span :class="['badge',c.status==='PUBLISHED'?'green':'']">{{ labels[c.status] }}</span></td><td class="actions"><button @click="selected=c;editor=pretty(c.definition)">查看 / 新版本</button><button @click="categoryCode=c.code;resource='schemas';tab='configuration'">Schema</button><button @click="safe(()=>categoryAvailability(c))">{{ c.categoryEnabled?'暂停发号':'恢复发号' }}</button></td></tr></tbody></table></section>
    <section v-if="selected" class="panel"><h2>类别定义 · {{ selected.code }}</h2><textarea v-model="editor" class="code-editor" rows="8"></textarea><button class="primary" @click="safe(()=>saveCategoryVersion(selected))">保存为新版本</button><p class="help">复用策略变更须与 Schema、Identity、编码规则一起审批发布。</p></section>
   </template>

   <template v-if="tab==='configuration'">
    <section class="panel"><div class="toolbar"><select aria-label="配置类型" v-model="resource"><option v-for="r in resources" :key="r[0]" :value="r[0]">{{ r[1] }}</option></select><button @click="newConfig">＋ 新建配置</button><span class="help">已发布版本不可修改，调整后保存为新版本。</span></div><table><thead><tr><th>配置代码</th><th>类别</th><th>版本</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="c in configs" :key="c.id"><td class="mono">{{ c.code }}</td><td>{{ c.categoryCode }}</td><td>V{{ c.version }}</td><td><span :class="['badge',c.status==='PUBLISHED'?'green':'']">{{ labels[c.status] }}</span></td><td><button @click="chooseConfig(c)">打开配置</button></td></tr></tbody></table><div v-if="!configs.length" class="empty">尚无此类配置。可新建草稿，并加入发布包。</div></section>
    <section class="panel" v-if="editor"><div class="panel-title"><h2>{{ selected?'配置版本 · V'+selected.version:'新建配置' }}</h2><span class="help">JSON 元数据编辑器</span></div><div class="form-grid"><label>配置代码<input v-model="configCode" :disabled="!!selected" required></label><label>所属类别<select v-model="categoryCode" :disabled="!!selected"><option v-for="c in uniqueCategories" :key="c.id" :value="c.code">{{ c.definition.name }} · {{ c.code }}</option></select></label></div><textarea aria-label="配置内容" v-model="editor" class="code-editor" rows="20"></textarea><div class="actions"><button v-if="!selected||selected.status==='DRAFT'" class="primary" :disabled="!!session.busy" @click="safe(()=>saveConfig())">保存草稿</button><button v-if="selected" :disabled="!!session.busy" @click="safe(()=>saveConfig(true))">保存为新版本</button></div></section>
   </template>

   <template v-if="tab==='preview'">
    <div class="preview-layout"><section class="panel"><h2>输入样本</h2><div class="form-grid"><label>物料类别<select aria-label="预览类别" v-model="categoryCode" @change="releaseId='';fillSample()"><option v-for="c in uniqueCategories" :key="c.id" :value="c.code">{{ c.definition.name }} · {{ c.code }}</option></select></label><label>发布版本<select v-model="releaseId" @change="fillSample"><option value="">当前已发布版本</option><option v-for="r in releases.filter(r=>r.categoryCode===categoryCode&&can('SCHEMA_DESIGN'))" :key="r.id" :value="r.id">V{{ r.version }} · {{ labels[r.status] }}</option></select></label><label class="span-2">调用系统代码<input v-model="callerCode"></label></div><label>标准属性 JSON<textarea aria-label="预览属性" v-model="sample" class="code-editor" rows="15"></textarea></label><button class="primary full-width" :disabled="!!session.busy" @click="safe(preview)">运行预览 <span>→</span></button><p class="help">不会写入发号账本，不会消耗正式流水。</p><details v-if="can('CALLER_MANAGE')" class="integration-console"><summary>调用方 API 联调</summary><p class="help">使用 Caller 凭据执行正式请求。返回号码后由调用方保存。</p><label>sourceRecordKey<input v-model="sourceKey"></label><label>Caller API Key<input v-model="callerKey" type="password" autocomplete="off"></label><button :disabled="!!session.busy||!!releaseId" @click="safe(issue)">通过 Caller API 正式发号</button></details></section>
    <section class="panel"><div class="panel-title"><h2>决策结果</h2><span v-if="result" class="badge green">{{ result.status?labels[result.status]:'预览完成' }}</span></div><div v-if="!result" class="preview-empty"><div class="identity-symbol">◎</div><h3>从输入到可解释的料号</h3><p>运行预览，查看标准化属性、派生结果、<br>Identity 以及每个编码段的计算依据。</p><div class="pipeline light">Schema <span>→</span> Identity <span>→</span> Code</div></div><template v-else><div class="number-result"><p>{{ result.status?'企业料号':'预期料号模板' }}</p><strong data-testid="material-no">{{ result.materialNo }}</strong><small v-if="result.sequenceReserved===false">流水占位符 · 未占号</small></div><details open v-if="result.identityCanonical"><summary>Material Identity</summary><code class="wrap">{{ result.identityCanonical }}</code><p class="help mono wrap">SHA-256 · {{ result.identityHash }}</p></details><details v-if="result.normalizedAttributes" open><summary>标准化属性</summary><pre>{{ pretty(result.normalizedAttributes) }}</pre></details><details v-if="result.derivedAttributes"><summary>派生结果</summary><pre>{{ pretty(result.derivedAttributes) }}</pre></details><details v-if="result.validationResults"><summary>Validation 结果</summary><pre>{{ pretty(result.validationResults) }}</pre></details><details v-if="result.codeSegments" open><summary>Code Rule Segment</summary><div v-for="seg in result.codeSegments" :key="seg.index" class="segment-row"><span>{{ String(seg.index+1).padStart(2,'0') }} · {{ seg.definition.type }}</span><b class="mono">{{ seg.output }}</b></div></details></template></section></div>
    <section class="panel"><h2>当前输入契约</h2><table><thead><tr><th>属性代码</th><th>名称</th><th>类型</th><th>必填</th><th>单位 / 精度</th></tr></thead><tbody><tr v-for="f in schemaFields" :key="f.attributeCode"><td class="mono">{{ f.attributeCode }}</td><td>{{ f.name }}</td><td>{{ f.type }}</td><td>{{ f.required?'是':'否' }}</td><td>{{ f.unit||'—' }} {{ f.scale!==undefined?' / '+f.scale:'' }}</td></tr></tbody></table></section>
   </template>

   <template v-if="tab==='releases'">
    <section class="panel"><div class="toolbar"><select v-model="categoryCode"><option v-for="c in uniqueCategories" :key="c.id" :value="c.code">{{ c.definition.name }} · {{ c.code }}</option></select><button v-if="can('RELEASE_SUBMIT')" @click="safe(newRelease)">＋ 新建发布包</button><span class="help">提交人与最终审批人须为不同成员。</span></div><table><thead><tr><th>发布包</th><th>类别</th><th>版本</th><th>状态</th><th>回归</th><th>操作</th></tr></thead><tbody><tr v-for="r in releases" :key="r.id"><td class="mono">{{ r.code }}</td><td>{{ r.categoryCode }}</td><td>4.0.{{ r.version }}</td><td><span :class="['badge',r.status==='PUBLISHED'?'green':'']">{{ labels[r.status] }}</span></td><td>{{ r.tests?r.tests.passed?'通过':'失败':'待测试' }}</td><td class="actions"><button @click="chooseRelease(r)">详情</button><template v-if="r.status==='DRAFT'"><button @click="safe(()=>releaseAction(r,'test'))">测试</button><button v-if="can('RELEASE_SUBMIT')" @click="safe(()=>releaseAction(r,'submit'))">提交审批</button></template><template v-if="r.status==='REVIEW'&&can('RELEASE_PUBLISH')"><button @click="safe(()=>releaseAction(r,'publish'))">审批并发布</button><button @click="safe(()=>releaseAction(r,'reject'))">驳回</button></template><button v-if="r.status==='PUBLISHED'&&can('RELEASE_PUBLISH')" @click="safe(()=>releaseAction(r,'retire'))">退役</button></td></tr></tbody></table></section>
    <section class="panel" v-if="releaseRefs"><h2>{{ selected?'发布包详情':'新建发布包' }}</h2><label>发布代码<input v-model="releaseCode" :disabled="!!selected"></label><div class="two-column"><label>依赖版本<textarea aria-label="发布依赖" v-model="releaseRefs" :readonly="selected&&selected.status!=='DRAFT'" class="code-editor" rows="15"></textarea></label><label>回归样本<textarea aria-label="发布样本" v-model="releaseSamples" :readonly="selected&&selected.status!=='DRAFT'" class="code-editor" rows="15"></textarea></label></div><button v-if="can('RELEASE_SUBMIT')&&(!selected||selected.status==='DRAFT')" class="primary" @click="safe(saveRelease)">保存发布包草稿</button><details v-if="result" open><summary>回归结果与版本 Diff</summary><pre>{{ pretty(result) }}</pre></details></section>
   </template>

   <template v-if="tab==='ledger'">
    <section class="panel"><form class="filter-grid" @submit.prevent="offset=0;safe(search)"><label>企业料号<input v-model="searchNo" placeholder="精确料号"></label><label>类别<select v-model="searchCategory"><option value="">所有授权类别</option><option v-for="c in uniqueCategories" :key="c.id" :value="c.code">{{ c.code }}</option></select></label><label>调用系统<input v-model="searchCaller" placeholder="ERP_PROD"></label><label>来源记录键<input v-model="searchSource" placeholder="sourceRecordKey"></label><label>Identity Hash<input v-model="searchHash" placeholder="SHA-256"></label><label>开始时间<input v-model="searchFrom" type="datetime-local"></label><label>结束时间<input v-model="searchTo" type="datetime-local"></label><button class="primary" :disabled="!!session.busy">查询账本</button></form></section>
    <section class="panel"><div class="panel-title"><h2>不可变发号事实</h2><span class="muted">共 {{ ledger.total }} 条</span></div><table><thead><tr><th>企业料号</th><th>类别</th><th>来源绑定</th><th>发号时间</th><th>解释</th></tr></thead><tbody><tr v-for="a in ledger.items" :key="a.assignmentId"><td class="mono accent">{{ a.materialNo }}</td><td>{{ a.categoryCode }}</td><td>{{ a.sourceBindingCount }}<span v-if="a.sourceBindingCount>1" class="badge green">已复用</span></td><td>{{ new Date(a.issuedAt).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai'}) }}</td><td><button @click="safe(()=>explain(a.assignmentId))">查看解释 →</button></td></tr></tbody></table><div v-if="!ledger.items.length" class="empty">没有符合条件的发号记录。</div><div class="pagination"><button :disabled="offset===0" @click="offset-=25;safe(search)">上一页</button><span>{{ offset+1 }}–{{ Math.min(offset+25,ledger.total) }}</span><button :disabled="offset+25>=ledger.total" @click="offset+=25;safe(search)">下一页</button></div></section>
    <section v-if="result" class="panel explanation"><div class="panel-title"><h2>料号解释</h2><span class="badge green">永久发号事实</span></div><div class="number-result"><strong>{{ result.materialNo }}</strong><small class="mono">{{ result.assignmentId }}</small></div><div class="chain"><span v-for="(step,i) in result.explanationChain" :key="step">{{ i+1 }} · {{ step }}</span></div><details open><summary>Caller / Source Binding</summary><pre>{{ pretty(result.sourceBindings) }}</pre></details><details open><summary>Raw Input → Input Profile → Normalized</summary><pre>{{ pretty({inputSnapshot:result.inputSnapshot,mappedSnapshot:result.mappedSnapshot,normalizedSnapshot:result.normalizedSnapshot,derivedSnapshot:result.derivedSnapshot}) }}</pre></details><details open><summary>Material Identity</summary><code class="wrap">{{ result.identityCanonical }}</code><small class="block mono wrap">{{ result.identityHash }}</small></details><details open><summary>Code Rule Segment / Validation</summary><pre>{{ pretty({segments:result.codeSegmentSnapshot,validation:result.validationSnapshot}) }}</pre></details><details><summary>发布版本与完整依赖快照</summary><pre>{{ pretty(result.configurationSnapshot) }}</pre></details></section>
   </template>

   <section v-if="tab==='audit'" class="panel"><h2>操作审计</h2><table><thead><tr><th>时间</th><th>对象 / 操作</th><th>类别</th><th>执行身份</th><th>请求 ID</th><th>详情</th></tr></thead><tbody><tr v-for="a in audits" :key="a.id"><td>{{ new Date(a.createdAt).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai'}) }}</td><td>{{ a.objectType }}<small class="block">{{ a.action }}</small></td><td>{{ a.categoryCode||'—' }}</td><td class="mono">{{ a.actor }}</td><td class="mono">{{ a.requestId }}</td><td><button @click="result=a">查看</button></td></tr></tbody></table><pre v-if="result">{{ pretty(result) }}</pre></section>

   <template v-if="tab==='monitor'&&monitor"><div class="stat-grid"><article><p>请求量</p><strong>{{ monitor.summary.requests }}</strong></article><article><p>失败率</p><strong>{{ (Number(monitor.summary.failureRate)*100).toFixed(1) }}<em>%</em></strong></article><article><p>冲突率</p><strong>{{ (Number(monitor.summary.conflictRate)*100).toFixed(1) }}<em>%</em></strong></article><article><p>P95 延迟</p><strong>{{ Number(monitor.summary.p95Ms).toFixed(1) }}<em>ms</em></strong></article></div><div class="two-column"><section class="panel"><h2>数据库健康</h2><div class="health-row"><span>数据库连接</span><b>{{ monitor.database.healthy?'正常':'异常' }}</b></div><div class="health-row"><span>共享数据库锁等待</span><b>{{ monitor.database.lockWaiting }}</b></div><h3>主要异常</h3><div v-for="e in monitor.errors" :key="e.code" class="health-row"><code>{{ e.code }}</code><b>{{ e.count }}</b></div><div v-if="!monitor.errors.length" class="empty">最近24小时没有异常记录。</div></section><section class="panel"><h2>Sequence 计数器</h2><table><thead><tr><th>类别 / 流水</th><th>周期</th><th>最后发放</th></tr></thead><tbody><tr v-for="q in monitor.sequences" :key="q.codeRuleVersionId+q.sequenceName+q.periodKey"><td>{{ q.categoryCode }}<small class="block">{{ q.sequenceName }}</small></td><td>{{ q.periodKey }}</td><td>{{ q.lastValue }}</td></tr></tbody></table></section></div><section class="panel"><h2>最近请求</h2><table><thead><tr><th>请求ID</th><th>Caller / 类别</th><th>状态</th><th>延迟</th><th>结果码</th></tr></thead><tbody><tr v-for="r in monitor.recentRequests" :key="r.id"><td class="mono">{{ r.requestId }}</td><td>{{ r.callerCode }}<small class="block">{{ r.categoryCode }}</small></td><td>{{ r.status }}</td><td>{{ r.latencyMs }} ms</td><td>{{ r.code }}</td></tr></tbody></table></section><section class="panel"><h2>请求与流水延迟</h2><div class="health-row"><span>正式发号 / 预览请求</span><b>{{ monitor.summary.assignmentRequests }} / {{ monitor.summary.previewRequests }}</b></div><div class="health-row"><span>Identity / 来源复用比例</span><b>{{ (Number(monitor.summary.reuseRate)*100).toFixed(1) }}%</b></div><div class="health-row"><span>API P50 / P95 / P99</span><b>{{ Number(monitor.summary.p50Ms).toFixed(1) }} / {{ Number(monitor.summary.p95Ms).toFixed(1) }} / {{ Number(monitor.summary.p99Ms).toFixed(1) }} ms</b></div><div class="health-row"><span>Sequence 分配 P95</span><b>{{ Number(monitor.summary.sequenceP95Ms).toFixed(2) }} ms</b></div></section><section class="panel"><h2>Caller System 健康调用情况</h2><table><thead><tr><th>调用系统</th><th>调用量</th><th>失败数</th><th>最近调用</th></tr></thead><tbody><tr v-for="c in monitor.callerHealth" :key="c.callerCode"><td>{{ c.callerCode }}</td><td>{{ c.requests }}</td><td>{{ c.failures }}</td><td>{{ new Date(c.lastCallAt).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai'}) }}</td></tr></tbody></table></section></template>

   <template v-if="tab==='access'">
    <section class="panel"><h2>成员与类别权限</h2><table><thead><tr><th>成员</th><th>角色</th><th>类别范围</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="m in members" :key="m.userId"><td>{{ m.name }} · {{ m.code }}</td><td>{{ m.roles.join(', ') }}</td><td>{{ m.categoryScope.join(', ') }}</td><td>{{ m.active?'有效':'已停用' }}</td><td class="actions"><button @click="chooseMember(m)">编辑</button><button @click="safe(()=>disableMember(m))">{{ m.active?'停用':'恢复' }}</button></td></tr></tbody></table><form class="form-grid" @submit.prevent="safe(saveMember)"><label>用户<select v-if="users.length" v-model="memberUser"><option v-for="u in users" :key="u.id" :value="u.id">{{ u.name }} · {{ u.code }}</option></select><input v-else v-model="memberUser" placeholder="用户UUID"></label><label>角色<select multiple v-model="memberRoles"><option v-for="r in roles" :key="r.code" :value="r.code">{{ r.code }}</option></select></label><label>类别范围<input v-model="memberScope" placeholder="* 或逗号分隔类别代码"></label><button class="primary">{{ selected?'保存成员':'添加成员' }}</button><button type="button" @click="selected=null;memberUser='';memberRoles=[]">新成员</button></form></section>
    <section class="panel"><h2>角色与权限</h2><div class="role-list"><details v-for="r in roles" :key="r.code"><summary>{{ r.code }}</summary><pre>{{ pretty(r.permissions) }}</pre></details></div><form class="inline-form" @submit.prevent="safe(createRole)"><label>自定义角色代码<input v-model="roleCode" required></label><label>权限代码 JSON<input v-model="rolePermissions" placeholder='["PREVIEW", "ASSIGNMENT_READ"]' required></label><button>创建角色</button></form></section>
    <template v-if="session.user.platformAdmin"><section class="panel"><h2>租户管理</h2><table><thead><tr><th>企业</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="t in tenantRows" :key="t.id"><td>{{ t.name }} · {{ t.code }}</td><td>{{ labels[t.status] }}</td><td class="actions"><button v-if="t.status!=='ARCHIVED'" @click="safe(()=>tenantStatus(t,t.status==='ACTIVE'?'SUSPENDED':'ACTIVE'))">{{ t.status==='ACTIVE'?'暂停':'启用' }}</button><button v-if="t.status!=='ARCHIVED'&&t.code!==session.tenant" @click="safe(()=>tenantStatus(t,'ARCHIVED'))">归档</button></td></tr></tbody></table><form class="inline-form" @submit.prevent="safe(createTenant)"><label>租户代码<input v-model="tenantCode" required></label><label>企业名称<input v-model="tenantName" required></label><button class="primary">创建租户</button></form></section><section class="panel"><h2>创建用户</h2><form class="form-grid" @submit.prevent="safe(createUser)"><label>用户代码<input v-model="createUserCode" required></label><label>姓名<input v-model="createUserName" required></label><label>初始密码<input v-model="createUserPassword" type="password" minlength="12" autocomplete="new-password" required></label><button>创建用户</button></form></section></template>
   </template>
   <footer>Material Identity & Number Governance <span>V4.0 · API First</span></footer>
   </main>
  </div>
 </div>
</template>
