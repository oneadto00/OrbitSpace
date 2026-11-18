import type { DeviceScope } from "@orbitspace/shared-types";

export const scopeNames: Record<DeviceScope, string> = {
  "metadata:write": "上传 Git 元信息",
  "patch:write": "上传主动共享的 Patch",
};
export const dateText = (value: string) => new Intl.DateTimeFormat("zh-CN", {
  dateStyle: "medium", timeStyle: "short",
}).format(new Date(value));
