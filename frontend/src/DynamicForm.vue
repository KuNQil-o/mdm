<script setup lang="ts">
import { computed } from "vue";
const props = defineProps<{
  bundle: any;
  model: any;
  references: any[];
  normalized?: any;
  decisions?: any;
}>();
const orderedFields = computed(() => {
  const ui = props.bundle.uiSchema || {};
  const order = (ui.order || []).map((f: any) =>
    typeof f === "string" ? f : f.code,
  );
  return (props.bundle.attributes || [])
    .map((f: any) => ({ ...f, ...(ui.fields?.[f.code] || {}) }))
    .sort((a: any, b: any) => {
      const ai = order.indexOf(a.code),
        bi = order.indexOf(b.code);
      return (
        (ai < 0 ? 999 : ai) - (bi < 0 ? 999 : bi) ||
        (a.order || 0) - (b.order || 0)
      );
    });
});
const groups = computed(() => [
  ...new Set<string>(
    orderedFields.value.map((f: any) => f.group || "物料属性"),
  ),
]);
function visible(f: any) {
  return props.decisions?.[f.code]?.visible ?? true;
}
function setNumber(code: string, value: string, unit: string) {
  props.model[code] = value === "" ? null : { value, unit };
}
function setRef(f: any, id: string) {
  props.model[f.code] = id ? { type: f.referenceType, id } : null;
}
</script>
<template>
  <section v-for="group in groups" :key="group" class="field-group">
    <h3>{{ group }}</h3>
    <div class="form-grid">
      <template
        v-for="f in orderedFields.filter(
          (f: any) => (f.group || '物料属性') === group,
        )"
        :key="f.code"
        ><label v-if="visible(f)"
          ><span
            >{{ f.label || f.code }}
            <b
              v-if="decisions?.[f.code]?.required ?? f.required"
              class="required"
              >*</b
            ><small v-if="f.derived">派生 · 只读</small></span
          ><input
            v-if="f.derived"
            :value="
              normalized?.[f.code]?.value ??
              normalized?.[f.code] ??
              '保存或预览后计算'
            "
            disabled
          />
          <div
            v-else-if="['DECIMAL', 'INTEGER'].includes(f.type)"
            class="number-input"
          >
            <input
              :aria-label="f.label || f.code"
              inputmode="decimal"
              :value="model[f.code]?.value ?? ''"
              @input="
                setNumber(
                  f.code,
                  ($event.target as HTMLInputElement).value,
                  model[f.code]?.unit || f.unit || '',
                )
              "
            /><select
              v-if="f.unit"
              :value="model[f.code]?.unit || f.unit"
              @change="
                setNumber(
                  f.code,
                  String(model[f.code]?.value ?? ''),
                  ($event.target as HTMLSelectElement).value,
                )
              "
            >
              <option
                v-for="(u, code) in bundle.units"
                :key="code"
                :value="code"
                v-show="u.dimension === bundle.units[f.unit]?.dimension"
              >
                {{ code }}
              </option>
            </select>
          </div>
          <select
            v-else-if="f.type === 'ENUM'"
            v-model="model[f.code]"
            :aria-label="f.label || f.code"
          >
            <option :value="null">请选择</option>
            <option
              v-for="o in f.options?.filter((o: any) => o.active !== false)"
              :key="o.code"
              :value="o.code"
            >
              {{ o.label || o.code }}
            </option></select
          ><select
            v-else-if="f.type === 'REFERENCE'"
            :value="model[f.code]?.id || ''"
            :aria-label="f.label || f.code"
            @change="setRef(f, ($event.target as HTMLSelectElement).value)"
          >
            <option value="">请选择</option>
            <option
              v-for="r in references.filter(
                (r) => r.type === f.referenceType && r.active,
              )"
              :key="r.id"
              :value="r.id"
            >
              {{ r.name }} · {{ r.id }}
            </option></select
          ><select
            v-else-if="f.type === 'BOOLEAN'"
            v-model="model[f.code]"
            :aria-label="f.label || f.code"
          >
            <option :value="null">未设置</option>
            <option :value="false">否</option>
            <option :value="true">是</option></select
          ><input
            v-else
            :type="f.type === 'DATE' ? 'date' : 'text'"
            v-model="model[f.code]"
            :aria-label="f.label || f.code"
            :maxlength="f.maxLength || 1000"
          /><small
            >{{ f.code
            }}<template v-if="f.unit"> · 标准单位 {{ f.unit }}</template></small
          ></label
        ></template
      >
    </div>
  </section>
</template>
