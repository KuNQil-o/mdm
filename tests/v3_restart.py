"""PERF-006: persist pending tasks/cursor, stop only owned processes, restart and verify recovery."""
from v3_support import *
import subprocess,time
ROOT=pathlib.Path(__file__).resolve().parents[1]
def run(script,env=None):subprocess.run(['bash',script],cwd=ROOT,check=True,env=env)
import os
run('scripts/stop.sh');runtime=dict(os.environ,WRITEBACK_WORKERS='false',SCAN_WORKERS='false');run('scripts/start.sh',runtime)
e=Session();r=Session('reviewer');tag='R'+uuid.uuid4().hex[:7].upper();fi=Fixture(e,r,tag,'RESTART',[field('name',required=True)],['name'],segments(['name']),{'name':'GOLDEN'},sequence=True,expected='GOLDEN-{流水:6}');key=fi.record({'name':'ISSUED'});a=fi.assign(key);pending_key=fi.record({'name':'PENDING'});task=fi.explain(a)['writebackTasks'][-1];assert task['state']=='PENDING';assert erp('/writeback/flat_material/'+key)['materialNo'] is None;job=e.call('/source-datasets/'+fi.dataset['id']+'/scan','POST',{});assert job['status']=='PENDING';before=fi.explain(a)['ledger'];run('scripts/stop.sh');run('scripts/start.sh');e=Session();fi.e=e
end=time.monotonic()+45
while time.monotonic()<end:
 after=e.call('/writeback-tasks/'+task['id']);job_after=e.call('/scan-jobs/'+job['id'])
 if after['state']=='SUCCEEDED' and job_after['status']=='COMPLETED':break
 time.sleep(.5)
assert after['state']=='SUCCEEDED',after;assert job_after['status']=='COMPLETED',job_after;assert job_after['cursorAfter'];assert fi.assign(key)['materialNo']==a['materialNo'];assert erp('/writeback/flat_material/'+key)['materialNo']==a['materialNo'];existing=fi.explain(a)['ledger']
for field in ['materialNo','identityCanonical','issuedAt','schemaVersionId','codeRuleVersionId','inputSnapshot']:assert before[field]==existing[field],field
items=e.call('/assignment-ledger');ours=[row for row in items if row['datasetCode']==fi.code];assert len(ours)==3 and len({row['materialNo'] for row in ours})==3,ours;assert any(row['sourceRecordKey']==pending_key for row in ours)
report={'ids':['PERF-006'],'status':'PASSED','pendingWritebackRecovered':True,'scanRecovered':True,'cursor':job_after['cursorAfter'],'immutableFieldsUnchanged':True,'ledgerCount':len(ours),'runId':tag};pathlib.Path('.runtime/v3-restart-evidence.json').write_text(dumps(report));print(dumps(report))
