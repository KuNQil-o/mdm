import { reactive } from "vue";
export const session = reactive({
  tenant: "",
  user: null as any,
  context: null as any,
  generation: 0,
  busy: 0,
  error: "",
  notice: "",
});
const uncertain = new Map<string, string>();
export async function api(
  path: string,
  method = "GET",
  body?: any,
  version?: any,
  background = false,
): Promise<any> {
  const generation = session.generation;
  const abort = new AbortController();
  const timer = setTimeout(() => abort.abort(), 15000);
  const raw = body === undefined ? undefined : stringify(body);
  const signature = method + path + raw + version;
  const headers: Record<string, string> = {
    "X-Tenant-Code": session.tenant,
    "X-Request-ID": crypto.randomUUID(),
  };
  if (raw !== undefined) headers["Content-Type"] = "application/json";
  if (method !== "GET")
    headers["Idempotency-Key"] =
      uncertain.get(signature) || crypto.randomUUID();
  if (version !== undefined) headers["If-Match"] = '"' + version + '"';
  if (!background) {
    session.busy++;
    session.error = "";
  }
  try {
    const res = await fetch(
      path.startsWith("/api/") ? path : "/api/v1" + path,
      { method, headers, body: raw, signal: abort.signal, cache: "no-store" },
    );
    const data = parse(await res.text());
    if (generation !== session.generation) throw new Error("CONTEXT_CHANGED");
    if (!res.ok) {
      uncertain.delete(signature);
      throw new Error(
        data.code +
          "：" +
          data.message +
          (data.errors?.length
            ? "\n" +
              data.errors
                .map((e: any) => e.fieldPath + " " + e.message)
                .join("\n")
            : ""),
      );
    }
    uncertain.delete(signature);
    return data;
  } catch (e: any) {
    if (e.message === "CONTEXT_CHANGED" || background) throw e;
    if (e instanceof TypeError || e.name === "AbortError") {
      uncertain.set(signature, headers["Idempotency-Key"]);
      session.error = "请求结果待确认。请使用相同操作重试，系统会复用幂等键。";
    } else session.error = e.message;
    throw e;
  } finally {
    clearTimeout(timer);
    if (!background) session.busy--;
  }
}
export class ExactDecimal {
  constructor(public __decimal: string) {}
  toString() {
    return this.__decimal;
  }
}
export function decimal(value: string) {
  if (!/^-?\d+(\.\d+)?([eE][+-]?\d+)?$/.test(value))
    throw new Error("请输入十进制数值");
  return new ExactDecimal(value);
}
export function parse(raw: string) {
  return (JSON.parse as any)(raw, (key: string, value: any, context: any) =>
    key === "value" && typeof value === "number" && context?.source
      ? decimal(context.source)
      : value,
  );
}
export function clone(value: any) {
  return parse(stringify(value));
}
export function stringify(value: any, indent?: number) {
  return JSON.stringify(
    value,
    (_k, v) =>
      v && typeof v === "object" && "__decimal" in v
        ? "__MDM_DECIMAL__" + v.__decimal + "__END__"
        : v,
    indent,
  ).replace(
    /"__MDM_DECIMAL__(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)__END__"/g,
    "$1",
  );
}
export async function download(id: string, name = "下载文件") {
  session.busy++;
  try {
    const r = await fetch("/api/v1/files/" + id, {
      headers: { "X-Tenant-Code": session.tenant },
      cache: "no-store",
    });
    if (!r.ok) {
      const e = await r.json();
      throw new Error(e.message);
    }
    const u = URL.createObjectURL(await r.blob());
    const a = document.createElement("a");
    a.href = u;
    a.download = name;
    a.click();
    setTimeout(() => URL.revokeObjectURL(u), 1000);
  } catch (e: any) {
    session.error = e.message;
  } finally {
    session.busy--;
  }
}
export function pretty(x: any) {
  return x === undefined ? "未设置" : stringify(x, 2);
}
export const labels: Record<string, string> = {
  DRAFT: "草稿",
  ACTIVE: "已生效",
  INACTIVE: "已停用",
  IN_REVIEW: "待审批",
  REVIEW: "待审批",
  PUBLISHED: "已发布",
  RETIRED: "已停用",
  APPROVED: "已批准",
  SUCCEEDED: "业务成功",
  PENDING: "待处理",
  FAILED: "失败",
  RETRY_WAIT: "等待重试",
  WAIT_CONFIRMATION: "待确认",
  RESULT_UNKNOWN: "结果未知",
  SUSPENDED: "已暂停",
  ARCHIVED: "已归档",
  UPLOADED: "已上传",
  VALIDATING: "预检中",
  READY: "预检完成",
  COMMITTING: "提交中",
  COMPLETED: "已完成",
  PARTIAL_FAILED: "部分失败",
  PAUSED: "已暂停",
  CANCELLED: "已取消",
  VALID: "预检通过",
  ERROR: "预检错误",
  NEW: "首次生效",
  CHANGE: "正式变更",
  DEACTIVATE: "停用",
  REACTIVATE: "重新启用",
  REVIEW_REQUIRED: "需要审核",
};
