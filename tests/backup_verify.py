"""将当前开发库备份到独立恢复库并比较核心业务摘要，不覆盖既有数据库。"""
import subprocess,pathlib,json,hashlib,time,sqlite3
root=pathlib.Path(__file__).resolve().parents[1]
def run(cmd):return subprocess.run(cmd,cwd=root,text=True,capture_output=True,check=True).stdout.strip()
def sql(db,q):return run(['docker','compose','exec','-T','db','psql','-U','mdm','-d',db,'-At','-v','ON_ERROR_STOP=1','-c',q])
tables=['business_user','tenant','tenant_member','role','category','metadata','schema_version','reference_entity','material','material_revision','material_request','material_projection','operation_log','outbox_event','delivery','external_identity','integration_system','mapping_version','number_sequence','material_unique_key','material_relation','approval_action','delivery_attempt','reconciliation_job','inbox_message','import_job','staging_row','file_record','idempotency_record','export_job','flyway_schema_history']
start=time.perf_counter();before={t:sql('mdm',f"select count(*),md5(coalesce(string_agg(md5(row_to_json(x)::text),'' order by md5(row_to_json(x)::text)),'')) from {t} x;") for t in tables}
out=run(['bash','scripts/backup.sh']);directory=pathlib.Path(out.split('：')[-1]);run(['sha256sum','-c',str(directory/'SHA256SUMS')]);name='mdm_restore_verify_'+time.strftime('%Y%m%d_%H%M%S',time.gmtime());run(['docker','compose','exec','-T','db','createdb','-U','mdm',name])
with open(directory/'mdm.dump','rb') as f:subprocess.run(['docker','compose','exec','-T','db','pg_restore','-U','mdm','-d',name,'--exit-on-error'],stdin=f,cwd=root,check=True)
after={t:sql(name,f"select count(*),md5(coalesce(string_agg(md5(row_to_json(x)::text),'' order by md5(row_to_json(x)::text)),'')) from {t} x;") for t in tables};assert before==after,[(t,before[t],after[t]) for t in tables if before[t]!=after[t]]
with sqlite3.connect(directory/'erp.sqlite') as c:assert c.execute('pragma integrity_check').fetchone()[0]=='ok'
report={'database':name,'backup':str(directory),'elapsedSeconds':round(time.perf_counter()-start,3),'tables':before,'allDigestsMatch':True,'erpSqliteIntegrity':'ok','productionRpoRtoVerified':False};(root/'.runtime/backup-evidence.json').write_text(json.dumps(report,ensure_ascii=False,indent=2));print(json.dumps(report,ensure_ascii=False,indent=2))
