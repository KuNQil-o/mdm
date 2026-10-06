"""ERP-owned save/request workflow. MDM remains the sole identity and numbering engine."""
import hashlib
import json
import os
import uuid
import urllib.request
import urllib.error
from psycopg import sql
from psycopg.types.json import Jsonb

TABLES = {'cloth', 'flat_material'}
CLOTH_FIELDS = {'weight_id', 'manufacturer_id', 'treatment_id', 'width_id', 'form_id', 'grade_id', 'remarks'}

class WorkflowError(Exception):
    def __init__(self, status, code, message, details=None):
        self.status = status
        self.body = {'success': False, 'code': code, 'message': message, **(details or {})}
        super().__init__(message)

def initialize(connect):
    with connect() as c:
        c.execute('''CREATE TABLE IF NOT EXISTS erp_mdm_integration(
            code text PRIMARY KEY, tenant_code text NOT NULL, dataset_code text NOT NULL,
            record_table text NOT NULL, key_env text NOT NULL, candidate_status text NOT NULL,
            enabled boolean NOT NULL DEFAULT true)''')
        c.execute('''CREATE TABLE IF NOT EXISTS erp_number_request(
            integration_code text REFERENCES erp_mdm_integration(code), source_record_key text,
            idempotency_key text NOT NULL, input_hash text NOT NULL, state text NOT NULL,
            result jsonb, error jsonb, attempts integer NOT NULL DEFAULT 0,
            updated_at timestamptz NOT NULL DEFAULT now(),
            PRIMARY KEY(integration_code,source_record_key))''')

def integration(c, code):
    cfg = c.execute('select * from erp_mdm_integration where code=%s and enabled', (code,)).fetchone()
    if not cfg or cfg['record_table'] not in TABLES:
        raise WorkflowError(409, 'INTEGRATION_NOT_READY', 'ERP 发号集成尚未初始化或已停用')
    return cfg

def record(c, cfg, key, lock=False):
    row = c.execute(sql.SQL('select * from {} where id=%s' + (' for update' if lock else '')).format(sql.Identifier(cfg['record_table'])), (key,)).fetchone()
    if not row:
        raise WorkflowError(404, 'SOURCE_NOT_FOUND', 'ERP 记录不存在')
    scope = row['status'] if cfg['record_table'] == 'cloth' else row['category']
    if scope != cfg['candidate_status']:
        raise WorkflowError(403, 'ERP_RECORD_SCOPE', '记录不属于此 ERP 发号集成')
    return row

def fingerprint(row, dumps):
    data = {k: v for k, v in row.items() if k not in {'material_no', 'version', 'updated_at'}}
    return hashlib.sha256(dumps(dict(sorted(data.items()))).encode()).hexdigest()

def call_mdm(cfg, key, idem):
    credential = os.environ.get(cfg['key_env'])
    if not credential:
        raise WorkflowError(503, 'MDM_CREDENTIAL_MISSING', '服务端未配置来源客户端凭据')
    base = os.environ.get('ERP_MDM_BASE_URL', 'http://127.0.0.1:8080').rstrip('/')
    req = urllib.request.Request(base + '/api/v1/material-numbers:assign',
        json.dumps({'datasetCode': cfg['dataset_code'], 'sourceRecordKey': key}).encode(),
        {'Content-Type': 'application/json', 'X-Tenant-Code': cfg['tenant_code'],
         'X-Source-Key': credential, 'Idempotency-Key': idem, 'X-Request-ID': str(uuid.uuid4())}, method='POST')
    try:
        with urllib.request.urlopen(req, timeout=12) as res:
            return json.load(res)
    except urllib.error.HTTPError as error:
        try:
            body = json.load(error)
        except (ValueError, OSError):
            body = {'code': 'MDM_HTTP_ERROR', 'message': 'MDM 接口返回错误'}
        raise WorkflowError(error.code, body.get('code', 'MDM_HTTP_ERROR'), body.get('message', 'MDM 发号失败'),
                            {'errors': body.get('errors', []), 'traceId': body.get('traceId')}) from None
    except (OSError, ValueError):
        raise WorkflowError(503, 'MDM_RESULT_UNKNOWN', '请求结果待确认；ERP 记录已保存，请对同一记录重试') from None

def perform(connect, dumps, code, key, values=None, assign=True):
    """Commit source before HTTP. Session lock serializes ERP operations, never blocks MDM reads/writeback."""
    with connect() as c:
        cfg = integration(c, code)
        c.execute('select pg_advisory_lock(hashtextextended(%s,0))', ('erp-request:' + code + ':' + key,))
        try:
            if values is not None:
                if cfg['record_table'] != 'cloth' or set(values) - CLOTH_FIELDS:
                    raise WorkflowError(422, 'INVALID_SOURCE_FIELD', '包含不允许编辑的 ERP 字段')
                old = c.execute('select * from cloth where id=%s for update', (key,)).fetchone()
                if old:
                    record(c, cfg, key)
                    request = c.execute('select * from erp_number_request where integration_code=%s and source_record_key=%s', (code, key)).fetchone()
                    issued = old['material_no'] or (request and request['result'])
                    if issued and any(k != 'remarks' and v != old[k] for k, v in values.items()):
                        raise WorkflowError(409, 'ISSUED_RECORD_LOCKED', '已发号记录的规格不能修改，请新建物料')
                    setters = [sql.SQL('{}=%s').format(sql.Identifier(k)) for k in values]
                    if setters:
                        c.execute(sql.SQL('update cloth set {}, version=version+1, updated_at=now() where id=%s').format(sql.SQL(',').join(setters)), list(values.values()) + [key])
                else:
                    cols = ['id', 'status'] + list(values)
                    c.execute(sql.SQL('insert into cloth ({}) values ({})').format(sql.SQL(',').join(map(sql.Identifier, cols)), sql.SQL(',').join(sql.Placeholder() for _ in cols)), [key, cfg['candidate_status']] + list(values.values()))
            current = record(c, cfg, key)
            c.commit()  # MDM reads the persisted ERP root + reference tables, not browser input.
            if not assign:
                return {'success': True, 'state': 'SAVED', 'record': current}
            digest = fingerprint(current, dumps)
            prior = c.execute('select * from erp_number_request where integration_code=%s and source_record_key=%s', (code, key)).fetchone()
            # Uncertain retries keep their key. Confirmed records re-enter MDM to detect reference/identity changes.
            idem = prior['idempotency_key'] if prior and prior['input_hash'] == digest and prior['state'] != 'CONFIRMED' else 'ERP-' + str(uuid.uuid4())
            c.execute('''insert into erp_number_request(integration_code,source_record_key,idempotency_key,input_hash,state,attempts)
                values(%s,%s,%s,%s,'REQUESTING',1) on conflict(integration_code,source_record_key)
                do update set idempotency_key=excluded.idempotency_key,input_hash=excluded.input_hash,
                state='REQUESTING',attempts=erp_number_request.attempts+1,error=null,updated_at=now()''', (code, key, idem, digest))
            c.commit()
            try:
                result = call_mdm(cfg, key, idem)
                if not result.get('assignmentId') or not result.get('materialNo'):
                    raise WorkflowError(422, 'MDM_NO_ASSIGNMENT', 'MDM 未返回正式发号台账，请检查已有 ERP 料号策略')
                # Preserve a known issued number even if saving it in ERP subsequently fails.
                c.execute('update erp_number_request set result=%s where integration_code=%s and source_record_key=%s', (Jsonb(result), code, key))
                c.commit()
                row = record(c, cfg, key, lock=True)
                if fingerprint(row, dumps) != digest:
                    raise WorkflowError(409, 'ERP_SOURCE_CHANGED', '申请期间 ERP 属性已改变，请核对原发号台账')
                if row['material_no'] not in (None, '', result['materialNo']):
                    raise WorkflowError(409, 'ERP_VALUE_CONFLICT', 'ERP 已有其他料号，不能覆盖')
                if row['material_no'] != result['materialNo']:
                    c.execute(sql.SQL('update {} set material_no=%s,version=version+1,updated_at=now() where id=%s').format(sql.Identifier(cfg['record_table'])), (result['materialNo'], key))
                c.execute("update erp_number_request set state='CONFIRMED',error=null,updated_at=now() where integration_code=%s and source_record_key=%s", (code, key))
                saved = record(c, cfg, key)
                c.commit()
                return {'success': True, 'state': 'CONFIRMED', 'record': saved, 'assignment': result}
            except WorkflowError as error:
                c.rollback()
                state = 'RESULT_UNKNOWN' if error.status >= 500 or error.body['code'] == 'MDM_RESULT_UNKNOWN' else 'FAILED'
                c.execute('update erp_number_request set state=%s,error=%s,updated_at=now() where integration_code=%s and source_record_key=%s', (state, Jsonb(error.body), code, key))
                c.commit()
                error.body.update({'sourceRecordKey': key, 'state': state, 'recordSaved': True})
                raise
        finally:
            c.rollback()
            c.execute('select pg_advisory_unlock(hashtextextended(%s,0))', ('erp-request:' + code + ':' + key,))
