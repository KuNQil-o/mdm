"""Restore MDM and independent ERP into new databases; never replace existing data."""
import subprocess,pathlib,json,time,uuid
ROOT=pathlib.Path(__file__).resolve().parents[1]
def run(cmd):return subprocess.run(cmd,cwd=ROOT,text=True,capture_output=True,check=True).stdout.strip()
def sql(db,q):return run(['docker','compose','exec','-T','db','psql','-U','mdm','-d',db,'-At','-v','ON_ERROR_STOP=1','-c',q])
def digest(db):
 tables=sql(db,"select tablename from pg_tables where schemaname='public' order by tablename").splitlines()
 queries=["select '"+t+"' as table_name,count(*) as row_count,md5(coalesce(string_agg(md5(row_to_json(x)::text),'' order by md5(row_to_json(x)::text)),'')) as digest from \""+t+"\" x" for t in tables]
 return json.loads(sql(db,"select json_object_agg(table_name,json_build_object('count',row_count,'digest',digest)) from ("+' UNION ALL '.join(queries)+') summary'))
start=time.perf_counter();before={db:digest(db) for db in ['mdm','mdm_erp_v3']};out=run(['bash','scripts/backup.sh']);directory=pathlib.Path(out.split('：')[-1]);subprocess.run(['sha256sum','-c','SHA256SUMS'],cwd=directory,check=True,capture_output=True);suffix=uuid.uuid4().hex[:8];restored={}
for source,file in [('mdm','mdm.dump'),('mdm_erp_v3','erp.dump')]:
 target=source+'_verify_'+suffix;run(['docker','compose','exec','-T','db','createdb','-U','mdm',target])
 with open(directory/file,'rb') as stream:subprocess.run(['docker','compose','exec','-T','db','pg_restore','-U','mdm','-d',target,'--exit-on-error'],cwd=ROOT,stdin=stream,check=True,capture_output=True)
 after=digest(target);assert before[source]==after,source+' restore differs';restored[source]={'database':target,'tables':len(after),'digests':after,'allDigestsMatch':True}
report={'status':'PASSED','elapsedSeconds':round(time.perf_counter()-start,3),'backup':str(directory),'restored':restored,'covers':'V3 objects, releases, full identity registry, ledgers, sequences, cursors, tasks, attempts, ERP facts, receipts, downstream facts','productionRpoRtoVerified':False};(ROOT/'.runtime/v3-backup-evidence.json').write_text(json.dumps(report,ensure_ascii=False,indent=2));print(json.dumps({k:v for k,v in report.items() if k!='restored'},ensure_ascii=False));print('TABLES',sum(x['tables'] for x in restored.values()))
