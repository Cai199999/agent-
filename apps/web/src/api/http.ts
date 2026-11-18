import axios from "axios";
import { tokenStorage } from "@/utils/tokenStorage";

let accessToken: string | null = tokenStorage.getToken();

export function setAccessToken(token: string | null): void {
  accessToken = token;
  tokenStorage.setToken(token);
}

export function getAccessToken(): string | null {
  return accessToken;
}

/**
 * 统一 Axios 封装：baseURL、JWT、trace、401 跳转。
 * access token 持久化在 tokenStorage（当前 localStorage 实现）。
 */
export const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || "/api/v1",
  timeout: 10_000,
});

http.interceptors.request.use((config) => {
  const traceId = crypto.randomUUID().replace(/-/g, "");
  config.headers["X-Request-Id"] = traceId;
  if (accessToken) {
    config.headers.Authorization = `Bearer ${accessToken}`;
  }
  return config;
});

/** 根据 HTTP 状态码返回人话提示。 */
export function httpStatusMessage(status?: number): string {
  switch (status) {
    case 400:
      return "请求参数有误，请检查后重试";
    case 401:
      return "登录已失效，请重新登录";
    case 403:
      return "您没有权限执行该操作";
    case 404:
      return "请求的资源不存在或已被删除";
    case 409:
      return "数据状态已变化，请刷新后重试";
    case 413:
      return "文件过大，请压缩后重新上传";
    case 422:
      return "请求内容无法处理，请调整后重试";
    case 429:
      return "操作过于频繁，请稍后再试";
    case 500:
    case 502:
    case 503:
    case 504:
      return "系统繁忙，请稍后重试";
    default:
      return "请求失败，请稍后重试";
  }
}

/** 判断后端或浏览器返回的是否为技术性英文错误，避免直接展示给用户。 */
function isTechnicalMessage(message?: string): boolean {
  if (!message) return true;
  return /internal server error|request failed with status code|network error|failed to fetch|timeout of \d+ms exceeded|econnrefused|socket hang up|请求失败\s*\(\d+\)/i.test(
    message,
  );
}

/** 给提示追加追踪号；同一提示只追加一次。 */
export function appendTrace(message: string, traceId?: string): string {
  if (!traceId || message.includes("追踪号")) return message;
  return `${message}（追踪号：${traceId}）`;
}

/** 把 Axios / SSE / 业务异常统一转换成可直接展示的中文提示。 */
export function toErrorMessage(error: any, fallback = "操作失败，请稍后重试"): string {
  if (error?.friendlyMessage) return error.friendlyMessage;
  const status = error?.response?.status;
  const traceId =
    error?.response?.data?.traceId ||
    error?.config?.headers?.["X-Request-Id"] ||
    error?.traceId;
  const raw = error?.response?.data?.message;
  if (raw && !isTechnicalMessage(raw)) {
    return appendTrace(raw, traceId);
  }
  if (error?.message && !isTechnicalMessage(error.message)) {
    return appendTrace(error.message, traceId);
  }
  if (status) {
    return appendTrace(httpStatusMessage(status), traceId);
  }
  if (error?.message && /timeout/i.test(error.message)) {
    return "请求超时，请稍后重试";
  }
  if (error?.message && /network|failed to fetch|load failed/i.test(error.message)) {
    return "网络连接异常，请检查网络后重试";
  }
  return fallback;
}

http.interceptors.response.use(
  (response) => response.data,
  (error) => {
    const status = error.response?.status;
    const traceId =
      error.response?.data?.traceId ||
      error.config?.headers?.["X-Request-Id"];
    const message = toErrorMessage(error, httpStatusMessage(status));
    error.friendlyMessage = appendTrace(message, traceId);
    error.traceId = traceId;
    error.status = status;

    if (status === 401) {
      accessToken = null;
      tokenStorage.clear();
      if (window.location.pathname !== "/login") {
        window.location.assign("/login");
      }
    }
    return Promise.reject(error);
  },
);
