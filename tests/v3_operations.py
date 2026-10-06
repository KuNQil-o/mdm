"""AC-06 / AC-12: polling alone issues and confirms an ERP-created record, with actual metrics."""
from v3_support import *
import time
start=time.monotonic();e=Session();r=Session('reviewer');tag='O'+uuid.uuid4().hex[:7].upper();fi=Fixture(e,r,tag,'POLL',[field('name',required=True)],['name'],segments(['name']),{'name':'GOLDEN'},sequence=True,expected='GOLDEN-{流水:6}');runtime=e.call('/source-datasets/'+fi.dataset['id']+'/runtime','PATCH',{'enabled':True,'intervalSeconds':2},1)
try:
 key=fi.record({'name':'ERPONLY'});deadline=time.monotonic()+40;actual=None
 while time.monotonic()<deadline:
  actual=erp('/writeback/flat_material/'+key)
  if actual['materialNo']:break
  time.sleep(.5)
 assert actual['materialNo'] and actual['materialNo'].startswith(fi.code+'-ERPONLY-'),actual
 ledgers=e.call('/assignment-ledger/search','POST',{'categoryCode':fi.code,'sourceRecordKey':key,'materialNo':actual['materialNo'],'writebackStatus':'SUCCEEDED'});assert ledgers['total']==1,ledgers;ledger=ledgers['items'][0]
 assert ledger['sourceRecordKey']==key;assert ledger['numberSource']=='GENERATED';x=e.call('/assignment-ledger/'+ledger['id']+'/explain');assert x['writebackAttempts'];assert x['operationLogs']
 metrics=e.call('/v3/monitoring');assert metrics['sourceOfRecord']=='ERP';assert any(row['sourceSystemId']==fi.source['id'] for row in metrics['sources']);assert isinstance(metrics['numbering']['issueP95Ms'],(float,int,decimal.Decimal));assert 'databaseLockWaiting' in metrics
 report={'status':'PASSED','ids':['AC-06','AC-12'],'sourceRecordKey':key,'materialNo':actual['materialNo'],'assignmentId':ledger['id'],'noHumanIssueRequest':True,'elapsedSeconds':round(time.monotonic()-start,3),'metrics':metrics['numbering']}
finally:
 current=e.call('/source-datasets/'+fi.dataset['id']+'/runtime');e.call('/source-datasets/'+fi.dataset['id']+'/runtime','PATCH',{'enabled':False},current['rowVersion'])
pathlib.Path('.runtime/v3-operations-evidence.json').write_text(dumps(report));print(dumps(report))
