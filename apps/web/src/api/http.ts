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
 * access token 仅保存在内存，不写入 localStorage。
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

http.interceptors.response.use(
  (response) => response.data,
  (error) => {
    if (error.response?.status === 401) {
      accessToken = null;
      tokenStorage.clear();
      if (window.location.pathname !== "/login") {
        window.location.assign("/login");
      }
    }
    return Promise.reject(error);
  },
);
