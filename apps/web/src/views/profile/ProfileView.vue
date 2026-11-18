<template>
  <div class="page-container">
    <div class="page-header">
      <h2 class="page-title">个人中心</h2>
    </div>

    <el-card v-loading="loading" class="profile-card">
      <el-alert
        title="这里的信息会用于报修建单：姓名、电话、项目/楼宇/房间/客户会随账号自动带出。"
        type="info"
        :closable="false"
        show-icon
        class="profile-tip"
      />
      <el-alert
        v-if="profile.reviewStatus === 'PENDING'"
        title="项目/楼宇/房间/客户绑定待管理员审核；审核通过前建单不会自动带出这些信息。"
        type="warning"
        :closable="false"
        show-icon
        class="profile-tip"
      />
      <el-alert
        v-if="profile.reviewStatus === 'REJECTED'"
        :title="`绑定审核未通过${profile.reviewRemark ? '：' + profile.reviewRemark : ''}`"
        type="error"
        :closable="false"
        show-icon
        class="profile-tip"
      />
      <el-form :model="form" label-width="110px">
        <el-form-item label="联系人" required>
          <el-input v-model="form.contactName" maxlength="100" placeholder="用于工单联系人" />
        </el-form-item>
        <el-form-item label="联系电话">
          <el-input
            v-model="form.phone"
            maxlength="32"
            :placeholder="profile.phoneMask ? `当前：${profile.phoneMask}，留空则不修改` : '请输入联系电话'"
          />
        </el-form-item>
        <el-form-item label="项目">
          <el-select v-model="form.projectId" clearable style="width: 100%" @change="onProjectChange">
            <el-option v-for="item in masterData.projects" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="楼宇">
          <el-select v-model="form.buildingId" clearable style="width: 100%" @change="onBuildingChange">
            <el-option v-for="item in buildingOptions" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="房间">
          <el-select v-model="form.roomId" clearable style="width: 100%">
            <el-option
              v-for="item in roomOptions"
              :key="item.id"
              :label="`${item.name}${item.extra ? ' · ' + item.extra : ''}`"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="客户">
          <el-select v-model="form.customerId" clearable style="width: 100%">
            <el-option
              v-for="item in customerOptions"
              :key="item.id"
              :label="`${item.name}${item.extra ? ' · ' + item.extra : ''}`"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="saving" @click="save">保存</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card v-if="canReview" v-loading="reviewLoading" class="profile-card review-card">
      <template #header>
        <span>待审核绑定（{{ pendingReviews.length }}）</span>
        <el-button link type="primary" class="refresh" @click="loadReviews">刷新</el-button>
      </template>
      <el-empty v-if="!pendingReviews.length" description="暂无待审核资料" :image-size="50" />
      <el-table v-else :data="pendingReviews" stripe>
        <el-table-column prop="displayName" label="用户" width="110" />
        <el-table-column prop="contactName" label="联系人" width="100" />
        <el-table-column prop="phoneMask" label="电话" width="120" />
        <el-table-column label="项目" width="110">
          <template #default="{ row }">{{ optionName(masterData.projects, row.projectId) }}</template>
        </el-table-column>
        <el-table-column label="楼宇/房间" min-width="150">
          <template #default="{ row }">
            {{ optionName(masterData.buildings, row.buildingId) }}
            {{ optionName(masterData.rooms, row.roomId) }}
          </template>
        </el-table-column>
        <el-table-column label="客户" width="110">
          <template #default="{ row }">{{ optionName(masterData.customers, row.customerId) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <el-button link type="success" @click="approve(row)">通过</el-button>
            <el-button link type="danger" @click="reject(row)">驳回</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  getProfile,
  listPendingProfiles,
  reviewProfile,
  updateProfile,
  type ProfileReview,
  type UserProfile,
} from "@/api/profile";
import { getMasterDataOptions, type MasterDataOptions } from "@/api/masterData";
import { toErrorMessage } from "@/api/http";
import { useAuthStore } from "@/stores/auth";

const auth = useAuthStore();

const loading = ref(false);
const saving = ref(false);
const profile = ref<UserProfile>({});
const masterData = ref<MasterDataOptions>({
  projects: [],
  buildings: [],
  rooms: [],
  customers: [],
  equipment: [],
  serviceItems: [],
});

const form = reactive({
  contactName: "",
  phone: "",
  projectId: "",
  buildingId: "",
  roomId: "",
  customerId: "",
});

const pendingReviews = ref<ProfileReview[]>([]);
const reviewLoading = ref(false);
const canReview = computed(() => auth.permissions.includes("admin:user"));

const buildingOptions = computed(() =>
  masterData.value.buildings.filter((item) => !form.projectId || item.parentId === form.projectId),
);
const roomOptions = computed(() =>
  masterData.value.rooms.filter((item) => !form.buildingId || item.parentId === form.buildingId),
);
const customerOptions = computed(() =>
  masterData.value.customers.filter((item) => !form.projectId || item.parentId === form.projectId),
);

function onProjectChange(): void {
  form.buildingId = "";
  form.roomId = "";
  form.customerId = "";
}

function onBuildingChange(): void {
  form.roomId = "";
}

function optionName(options: { id: string; name: string }[], id?: string | null): string {
  if (!id) return "-";
  return options.find((item) => item.id === id)?.name || id;
}

async function loadReviews(): Promise<void> {
  if (!canReview.value) return;
  reviewLoading.value = true;
  try {
    pendingReviews.value = await listPendingProfiles();
  } catch (error: any) {
    ElMessage.error(toErrorMessage(error, "加载待审核资料失败"));
  } finally {
    reviewLoading.value = false;
  }
}

async function approve(row: ProfileReview): Promise<void> {
  try {
    await reviewProfile(row.userId, true);
    ElMessage.success("已通过绑定审核");
    await loadReviews();
  } catch (error: any) {
    ElMessage.error(toErrorMessage(error, "审核失败"));
  }
}

async function reject(row: ProfileReview): Promise<void> {
  try {
    const result = await ElMessageBox.prompt("请输入驳回原因", "驳回绑定", {
      inputType: "textarea",
      confirmButtonText: "驳回",
      cancelButtonText: "取消",
    });
    await reviewProfile(row.userId, false, result.value);
    ElMessage.success("已驳回");
    await loadReviews();
  } catch {
    // 取消
  }
}

async function load(): Promise<void> {
  loading.value = true;
  try {
    const [profileData, options] = await Promise.all([getProfile(), getMasterDataOptions()]);
    profile.value = profileData;
    masterData.value = options;
    form.contactName = profileData.contactName || "";
    form.projectId = profileData.projectId || "";
    form.buildingId = profileData.buildingId || "";
    form.roomId = profileData.roomId || "";
    form.customerId = profileData.customerId || "";
    void loadReviews();
  } catch (error: any) {
    ElMessage.error(toErrorMessage(error, "加载个人资料失败"));
  } finally {
    loading.value = false;
  }
}

async function save(): Promise<void> {
  if (!form.contactName.trim()) {
    ElMessage.warning("请填写联系人");
    return;
  }
  saving.value = true;
  try {
    profile.value = await updateProfile({
      contactName: form.contactName.trim(),
      phone: form.phone.trim() || null,
      projectId: form.projectId || null,
      buildingId: form.buildingId || null,
      roomId: form.roomId || null,
      customerId: form.customerId || null,
    });
    form.phone = "";
    ElMessage.success("个人资料已保存");
  } catch (error: any) {
    ElMessage.error(toErrorMessage(error, "保存个人资料失败"));
  } finally {
    saving.value = false;
  }
}

onMounted(load);
</script>

<style scoped>
.profile-card {
  max-width: 680px;
}
.profile-tip {
  margin-bottom: 20px;
}
.review-card {
  margin-top: 16px;
}
.refresh {
  float: right;
}
</style>
