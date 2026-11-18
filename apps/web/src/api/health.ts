import { http } from "@/api/http";

export interface AiScopeHealth {
  scope: string;
  state: string;
  failures: number;
  openSeconds: number;
  remainingSeconds: number;
}

export interface AiHealth {
  status: "UP" | "DEGRADED";
  scopes: Record<string, AiScopeHealth>;
}

/** AI 熔断器状态；用于在对话页提示“AI 暂不可用，可转表单”。 */
export async function getAiHealth(): Promise<AiHealth> {
  const body = (await http.get("/health/ai")) as any;
  if (body.code !== 0) {
    throw new Error(body.message || "获取 AI 状态失败");
  }
  return body.data as AiHealth;
}
