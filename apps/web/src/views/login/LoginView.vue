<template>
  <div class="login-page">
    <el-card class="login-card">
      <h2 class="login-title">IT 运维工单智能 Agent</h2>
      <el-form :model="form" label-width="72px" @submit.prevent="onLogin">
        <el-form-item label="账号">
          <el-input v-model="form.username" autocomplete="username" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            autocomplete="current-password"
            @keyup.enter="onLogin"
          />
        </el-form-item>
        <el-button type="primary" class="login-button" :loading="loading" @click="onLogin">
          登录
        </el-button>
      </el-form>
      <p class="login-hint">
        开发账号 admin；密码在项目 .env 的 DEV_ADMIN_PASSWORD。
      </p>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { useAuthStore } from "@/stores/auth";

const router = useRouter();
const auth = useAuthStore();
const loading = ref(false);
const form = reactive({ username: "admin", password: "" });

async function onLogin(): Promise<void> {
  if (!form.username || !form.password) {
    ElMessage.warning("请输入账号和密码");
    return;
  }
  loading.value = true;
  try {
    await auth.login(form.username, form.password);
    ElMessage.success("登录成功");
    await router.push("/tickets");
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.message || error?.message || "登录失败");
  } finally {
    loading.value = false;
  }
}
</script>

<style scoped>
.login-page {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
}
.login-card {
  width: 380px;
}
.login-title {
  margin: 0 0 20px;
  text-align: center;
}
.login-button {
  width: 100%;
}
.login-hint {
  margin: 12px 0 0;
  color: #909399;
  font-size: 12px;
  text-align: center;
}
</style>
