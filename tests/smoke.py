from http_client import Client
import json
c=Client();r=Client('reviewer');s=c.call('/categories/GLASS_CLOTH/schema')
b={'schemaVersionId':s['id'],'categoryCode':'GLASS_CLOTH','materialName':'7628玻璃布 · 开发验收','baseUnitCode':'m','attributes':{'model':'7628','width':{'value':1270,'unit':'mm'},'basisWeight':{'value':210,'unit':'g/m2'},'manufacturer':{'type':'SUPPLIER','id':'SUP-001'},'certified':False}}
p=c.call('/material-numbers:preview','POST',b);assert p['candidateMaterialNo']=='GC-7628-1270-HONGHE' and p['reserved'] is False
m=c.call('/materials','POST',b,expected=201);assert m['materialNo'] is None
q=c.call('/materials/'+m['id']+'/change-requests','POST',{'kind':'NEW'},m['rowVersion']);q=c.call('/requests/'+q['id']+'/submit','POST',{},q['rowVersion'])
c.call('/requests/'+q['id']+'/approve','POST',{},q['rowVersion'],expected=403)
r.call('/requests/'+q['id']+'/approve','POST',{},q['rowVersion'])
m=c.call('/materials/'+m['id']);assert m['status']=='ACTIVE' and m['materialNo']==p['candidateMaterialNo']
assert len(c.call('/materials/'+m['id']+'/history'))==3
search=c.call('/materials/search','POST',{'categoryCode':'GLASS_CLOTH','filters':[{'field':'attributes.width','op':'BETWEEN','value':[1.2,1.3],'unit':'m'}]});assert any(x['id']==m['id'] for x in search['items'])
print(json.dumps({'status':'PASS','materialId':m['id'],'materialNo':m['materialNo'],'rowVersion':m['rowVersion'],'historyRevisions':3,'normalizedUnitSearch':'PASS','selfApprovalRejected':'PASS'},ensure_ascii=False))
