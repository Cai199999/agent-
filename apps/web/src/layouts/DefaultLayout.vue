<template>
  <el-container class="app-layout">
    <el-aside width="220px" class="app-aside">
      <div class="brand">运维工单 Agent</div>
      <el-menu :default-active="activeMenu" router background-color="#1f2d3d" text-color="#c0c4cc" active-text-color="#409eff">
        <el-menu-item index="/dashboard">工作台</el-menu-item>
        <el-menu-item index="/tickets">工单管理</el-menu-item>
        <el-menu-item index="/chat">AI 助手</el-menu-item>
        <el-menu-item v-if="can('knowledge:manage')" index="/knowledge">知识库</el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="app-header">
        <span>IT 运维工单智能 Agent</span>
        <div class="header-user">
          <el-badge :value="unreadCount" :hidden="unreadCount === 0" :max="99">
            <el-button circle @click="openNotifications">
              <el-icon><Bell /></el-icon>
            </el-button>
          </el-badge>
          <span>{{ auth.user?.displayName || auth.user?.username || "未登录" }}</span>
          <el-button link type="primary" @click="logout">退出</el-button>
        </div>
      </el-header>
      <el-main>
        <router-view />
      </el-main>
    </el-container>

    <el-drawer v-model="notificationVisible" title="通知中心" size="380px">
      <div class="notification-toolbar">
        <el-switch v-model="unreadOnly" active-text="只看未读" @change="loadNotifications" />
        <el-button link type="primary" :disabled="unreadCount === 0" @click="readAll">全部已读</el-button>
      </div>
      <div v-loading="notificationLoading" class="notification-list">
        <el-empty v-if="!notifications.length" description="暂无通知" :image-size="60" />
        <div
          v-for="item in notifications"
          :key="item.id"
          :class="['notification-item', { unread: !item.readAt }]"
          @click="readOne(item)"
        >
          <div class="notification-head">
            <span class="notification-title">{{ item.title }}</span>
            <span class="notification-time">{{ formatTime(item.createdAt) }}</span>
          </div>
          <div class="notification-content">{{ item.content }}</div>
        </div>
      </div>
    </el-drawer>
  </el-container>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { Bell } from "@element-plus/icons-vue";
import { ElMessage } from "element-plus";
import { useAuthStore } from "@/stores/auth";
import {
  fetchUnreadCount,
  listNotifications,
  markAllNotificationsRead,
  markNotificationRead,
  type NotificationItem,
} from "@/api/notifications";

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const activeMenu = computed(() => route.path);

function can(permission: string): boolean {
  return auth.permissions.includes(permission);
}

const unreadCount = ref(0);
const notificationVisible = ref(false);
const notificationLoading = ref(false);
const notifications = ref<NotificationItem[]>([]);
const unreadOnly = ref(false);
let timer: number | undefined;

function formatTime(value?: string | null): string {
  return value ? new Date(value).toLocaleString() : "";
}

async function refreshUnread(): Promise<void> {
  try {
    unreadCount.value = await fetchUnreadCount();
  } catch {
    // 通知轮询失败不影响主流程
  }
}

async function loadNotifications(): Promise<void> {
  notificationLoading.value = true;
  try {
    const result = await listNotifications({ unread: unreadOnly.value, pageSize: 50 });
    notifications.value = result.items;
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.message || error?.message || "加载通知失败");
  } finally {
    notificationLoading.value = false;
  }
}

function openNotifications(): void {
  notificationVisible.value = true;
  void loadNotifications();
  void refreshUnread();
}

async function readOne(item: NotificationItem): Promise<void> {
  if (item.readAt) return;
  try {
    await markNotificationRead(item.id);
    item.readAt = new Date().toISOString();
    unreadCount.value = Math.max(0, unreadCount.value - 1);
    if (unreadOnly.value) {
      notifications.value = notifications.value.filter((row) => row.id !== item.id);
    }
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.message || error?.message || "操作失败");
  }
}

async function readAll(): Promise<void> {
  try {
    await markAllNotificationsRead();
    unreadCount.value = 0;
    const now = new Date().toISOString();
    notifications.value = notifications.value.map((item) => ({ ...item, readAt: item.readAt ?? now }));
    if (unreadOnly.value) {
      notifications.value = [];
    }
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.message || error?.message || "操作失败");
  }
}

function logout(): void {
  auth.logout();
  router.push("/login");
}

onMounted(() => {
  void refreshUnread();
  timer = window.setInterval(refreshUnread, 30_000);
});

onBeforeUnmount(() => {
  if (timer) window.clearInterval(timer);
});
</script>

<style scoped>
.app-layout {
  height: 100%;
}
.app-aside {
  background: #1f2d3d;
  color: #fff;
}
.brand {
  height: 56px;
  line-height: 56px;
  padding: 0 18px;
  font-weight: 600;
  color: #fff;
}
.app-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
}
.header-user {
  display: flex;
  align-items: center;
  gap: 12px;
}
.notification-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.notification-list {
  min-height: 120px;
}
.notification-item {
  padding: 10px 12px;
  border-radius: 6px;
  cursor: pointer;
  border-bottom: 1px solid #f2f3f5;
}
.notification-item:hover {
  background: #f5f7fa;
}
.notification-item.unread {
  background: #ecf5ff;
}
.notification-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.notification-title {
  font-size: 14px;
  font-weight: 500;
}
.notification-time {
  color: #909399;
  font-size: 12px;
  flex: none;
}
.notification-content {
  margin-top: 4px;
  color: #606266;
  font-size: 13px;
  line-height: 1.5;
}
</style>
