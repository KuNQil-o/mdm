const $ = id => document.getElementById(id);
const fields = ['manufacturer_id', 'weight_id', 'treatment_id', 'width_id', 'form_id', 'grade_id'];
const states = {SAVED:'已保存，待申请', REQUESTING:'申请中', CONFIRMED:'已回填，可继续 ERP 业务', FAILED:'失败，请修正后重试', RESULT_UNKNOWN:'结果待确认，请重试同一记录'};
let refs = {}, records = [], saved = false, busy = false;
function options(id, rows, label) {
  $(id).replaceChildren(...rows.map(row => { const o = document.createElement('option'); o.value = row.id; o.textContent = label(row); return o; }));
}
function weights() {
  options('weight_id', (refs.cloth_weight || []).filter(r => r.type_id === $('cloth-type').value), r => `${r.cloth_code} · ${r.weight} ${r.unit}`);
}
function fresh() {
  saved = false;
  $('record-key').value = crypto.randomUUID(); $('material-no').value = ''; $('remarks').value = '';
  $('form-title').textContent = '新建玻璃布'; $('business-state').textContent = '待保存';
  for (const id of fields.concat('cloth-type')) $(id).disabled = false;
  $('retry').disabled = true;
}
function message(text, error = false) {
  $('message').hidden = false; $('message').className = error ? 'error' : ''; $('message').textContent = text;
}
async function api(path, body) {
  const response = await fetch('/erp/api/' + path, body ? {method:'POST', headers:{'Content-Type':'application/json'},body:JSON.stringify(body)} : {});
  const data = await response.json();
  if (!response.ok) { const error = new Error(data.code + '：' + (data.message || '操作失败')); error.data = data; throw error; }
  return data;
}
function details(data) { $('details-panel').hidden = false; $('details').textContent = JSON.stringify(data, null, 2); }
function open(row) {
  saved = true; $('record-key').value = row.id; $('remarks').value = row.remarks || ''; $('material-no').value = row.material_no || '';
  const weight = refs.cloth_weight.find(w => w.id === row.weight_id);
  $('cloth-type').value = weight?.type_id || ''; weights();
  for (const id of fields) $(id).value = row[id] || '';
  for (const id of fields.concat('cloth-type')) $(id).disabled = !!row.material_no || !!row.assignment;
  $('retry').disabled = false; $('form-title').textContent = '查看 / 维护玻璃布';
  $('business-state').textContent = states[row.request_state] || (row.material_no ? states.CONFIRMED : states.SAVED);
}
function ref(name, id, label) { const row = (refs[name] || []).find(x => x.id === id); return row ? label(row) : id || '未填'; }
async function refresh() {
  records = await api('cloth?integration=' + encodeURIComponent($('integration').value));
  $('records').replaceChildren(); $('empty').hidden = records.length > 0;
  for (const row of records) {
    const tr = document.createElement('tr');
    const texts = [row.id, ref('manufacturer',row.manufacturer_id,r => r.name), ref('cloth_weight',row.weight_id,r => `${r.cloth_code} / ${r.weight} ${r.unit}`),ref('width_ref',row.width_id,r => `${r.width} ${r.unit}`),row.material_no || '待编号',states[row.request_state] || (row.material_no ? states.CONFIRMED : states.SAVED)];
    for (const text of texts) { const td = document.createElement('td'); td.textContent = text; tr.append(td); }
    const td = document.createElement('td');
    for (const [label, fn] of [['打开',() => open(row)],['详情',() => details(row)]]) { const b = document.createElement('button'); b.textContent = label; b.onclick = fn; td.append(b); }
    tr.append(td); $('records').append(tr);
  }
}
async function action(kind) {
  if (busy) return;
  if (kind !== 'retry' && !$('form').reportValidity()) return;
  busy = true; $('integration').disabled = true; const buttons = [...document.querySelectorAll('button')]; buttons.forEach(b => b.disabled = true);
  try {
    $('business-state').textContent = kind === 'save' ? '正在保存…' : 'ERP 保存 → MDM 校验发号 → ERP 回填…';
    const integration = $('integration').value, key = $('record-key').value;
    const result = kind === 'retry' ? await api('cloth/' + encodeURIComponent(key) + '/request-number',{integration}) : await api('cloth',{integration,id:key,fields:Object.fromEntries(fields.concat('remarks').map(id => [id,$(id).value])),assign:kind !== 'save'});
    saved = true; details(result);
    message(result.state === 'CONFIRMED' ? `料号 ${result.record.material_no} 已自动写入 ERP，可继续原业务流程。` : 'ERP 记录已保存，可点击“申请 / 重试料号”。');
    await refresh(); open(records.find(r => r.id === key) || result.record);
  } catch(error) {
    if (error.data?.recordSaved) saved = true;
    message(error.message + (error.data?.recordSaved ? '\nERP 记录已保留；修正数据后重试同一来源键。' : ''), true);
    details(error.data || {message:error.message});
    $('business-state').textContent = states[error.data?.state] || '操作失败；可刷新列表确认';
    // Keep the same client-generated source key after transport failure.
    try { await refresh(); const row = records.find(r => r.id === $('record-key').value); if (row) open(row); } catch {}
  } finally {
    busy = false; $('integration').disabled = false; buttons.forEach(b => b.disabled = false); $('retry').disabled = !saved;
  }
}
$('form').onsubmit = e => {e.preventDefault(); action('assign');};
$('save').onclick = () => action('save'); $('retry').onclick = () => action('retry');
$('new').onclick = fresh; $('refresh').onclick = () => refresh().catch(e => message(e.message,true));
$('cloth-type').onchange = weights; $('integration').onchange = () => {fresh(); refresh().catch(e => message(e.message,true));};
(async () => {
  try {
    const cfg = await api('config'); refs = cfg.references;
    options('integration',cfg.integrations.filter(c => c.record_table === 'cloth').map(c => ({...c,id:c.code})), c => `${c.tenant_code} · ${c.dataset_code}`);
    if (!$('integration').value) {message('尚未初始化 ERP 演示配置，请运行 scripts/seed.sh。',true); $('save').disabled = $('save-assign').disabled = true; return;}
    options('manufacturer_id',refs.manufacturer,r => `${r.name} · ${r.code}`);
    options('cloth-type',refs.cloth_type,r => r.code); weights();
    options('treatment_id',refs.treatment,r => r.code);
    options('width_id',refs.width_ref,r => `${r.width} ${r.unit}`);
    options('form_id',refs.form_ref,r => r.code); options('grade_id',refs.grade_ref,r => r.code);
    fresh(); await refresh();
  } catch(e) {message(e.message,true);}
})();
