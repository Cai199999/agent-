import { defineStore } from "pinia";
import { http, setAccessToken } from "@/api/http";
import { tokenStorage } from "@/utils/tokenStorage";

export interface UserView {
  id: string;
  username: string;
  displayName: string;
}

interface AuthState {
  accessToken: string | null;
  user: UserView | null;
  roles: string[];
  permissions: string[];
}

export const useAuthStore = defineStore("auth", {
  state: (): AuthState => ({
    accessToken: tokenStorage.getToken(),
    user: tokenStorage.getUser(),
    roles: [],
    permissions: [],
  }),
  getters: {
    isAuthenticated: (state) => Boolean(state.accessToken),
  },
  actions: {
    async login(username: string, password: string): Promise<void> {
      const body = (await http.post("/auth/login", { username, password })) as any;
      if (body.code !== 0) {
        throw new Error(body.message || "登录失败");
      }
      const data = body.data;
      this.accessToken = data.accessToken;
      this.user = data.user;
      this.roles = data.roles ?? [];
      this.permissions = data.permissions ?? [];
      setAccessToken(data.accessToken);
      tokenStorage.setUser(data.user);
    },
    async restore(): Promise<void> {
      if (!this.accessToken) return;
      setAccessToken(this.accessToken);
      const body = (await http.get("/auth/me")) as any;
      if (body.code !== 0) {
        throw new Error(body.message || "登录态失效");
      }
      this.user = body.data.user;
      this.roles = body.data.roles ?? [];
      this.permissions = body.data.permissions ?? [];
      tokenStorage.setUser(body.data.user);
    },
    logout(): void {
      this.accessToken = null;
      this.user = null;
      this.roles = [];
      this.permissions = [];
      setAccessToken(null);
      tokenStorage.clear();
    },
  },
});
