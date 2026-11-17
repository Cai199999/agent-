/**
 * 登录态持久化抽象。
 * 当前使用 localStorage；后续可替换为 httpOnly Cookie + refresh token，
 * 调用方只依赖本模块，不直接操作存储键。
 */

const TOKEN_KEY = "agent_gongdan_access_token";
const USER_KEY = "agent_gongdan_user";

export interface StoredUser {
  id: string;
  username: string;
  displayName: string;
}

export const tokenStorage = {
  getToken(): string | null {
    try {
      return window.localStorage.getItem(TOKEN_KEY);
    } catch {
      return null;
    }
  },

  setToken(token: string | null): void {
    try {
      if (token) {
        window.localStorage.setItem(TOKEN_KEY, token);
      } else {
        window.localStorage.removeItem(TOKEN_KEY);
      }
    } catch {
      // 隐私模式等场景下降级为内存态
    }
  },

  getUser(): StoredUser | null {
    try {
      const raw = window.localStorage.getItem(USER_KEY);
      return raw ? (JSON.parse(raw) as StoredUser) : null;
    } catch {
      return null;
    }
  },

  setUser(user: StoredUser | null): void {
    try {
      if (user) {
        window.localStorage.setItem(USER_KEY, JSON.stringify(user));
      } else {
        window.localStorage.removeItem(USER_KEY);
      }
    } catch {
      // ignore
    }
  },

  clear(): void {
    this.setToken(null);
    this.setUser(null);
  },
};
