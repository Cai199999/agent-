<template>
  <el-dialog v-model="visible" title="报修 / 报事" width="640px">
    <el-form v-loading="loading" :model="form" label-width="100px">
      <el-form-item label="报修人">
        <span>{{ profile.contactName || "-" }}</span>
        <span v-if="profile.phoneMask" class="profile-phone">{{ profile.phoneMask }}</span>
        <router-link to="/profile" class="profile-link">完善个人信息</router-link>
      </el-form-item>
      <el-form-item label="描述" required>
        <el-input
          v-model="form.description"
          type="textarea"
          :rows="4"
          maxlength="2000"
          show-word-limit
          placeholder="请描述故障现象、影响范围，标题会根据描述自动生成"
        />
      </el-form-item>
      <el-form-item label="类型" required>
        <el-select v-model="form.ticketTypeCode" style="width: 100%">
          <el-option v-for="item in typeOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="位置类型" required>
        <el-radio-group v-model="form.locationType" @change="onLocationTypeChange">
          <el-radio-button label="INDOOR">室内</el-radio-button>
          <el-radio-button label="OUTDOOR">室外</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <template v-if="form.locationType === 'INDOOR'">
        <el-form-item label="楼宇" required>
          <el-select v-model="form.buildingId" style="width: 100%" @change="onBuildingChange">
            <el-option v-for="item in buildingOptions" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="房间" required>
          <el-select v-model="form.roomId" style="width: 100%">
            <el-option
              v-for="item in roomOptions"
              :key="item.id"
              :label="`${item.name}${item.extra ? ' · ' + item.extra : ''}`"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
      </template>
      <el-form-item label="位置" required>
        <el-input v-model="form.locationText" placeholder="如 A栋2号梯 / 东门地下车库" />
      </el-form-item>
      <el-form-item label="详细地址">
        <el-input v-model="form.addressText" placeholder="可选" />
      </el-form-item>
      <el-form-item label="现场图片">
        <el-upload
          v-model:file-list="fileList"
          list-type="picture-card"
          accept="image/*"
          :auto-upload="false"
          multiple
          :on-change="onFileChange"
          :on-remove="onFileRemove"
        >
          <span class="upload-plus">+</span>
        </el-upload>
        <div class="upload-hint">可选，支持手机拍摄或相册选择</div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">提交</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import type { UserProfile } from "@/api/profile";
import type { MasterDataOptions } from "@/api/masterData";

const props = defineProps<{
  modelValue: boolean;
  profile: UserProfile;
  masterData: MasterDataOptions;
  loading: boolean;
  saving: boolean;
}>();

const emit = defineEmits<{
  (event: "update:modelValue", value: boolean): void;
  (event: "submit", value: { payload: Record<string, unknown>; files: File[] }): void;
}>();

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit("update:modelValue", value),
});

const typeOptions = [
  { label: "电梯故障", value: "ELEVATOR" },
  { label: "空调故障", value: "HVAC" },
  { label: "水泵故障", value: "PUMP" },
  { label: "消防故障", value: "FIRE" },
  { label: "门禁故障", value: "ACCESS" },
  { label: "网络故障", value: "NETWORK" },
  { label: "电气故障", value: "ELECTRICAL" },
  { label: "通用", value: "GENERAL" },
  { label: "其他", value: "OTHER" },
];

const form = reactive({
  description: "",
  ticketTypeCode: "ELEVATOR",
  locationType: "INDOOR",
  locationText: "",
  buildingId: "",
  roomId: "",
  addressText: "",
});

const fileList = ref<any[]>([]);
const files = ref<File[]>([]);

const buildingOptions = computed(() =>
  props.masterData.buildings.filter((item) => !props.profile.projectId || item.parentId === props.profile.projectId),
);
const roomOptions = computed(() =>
  props.masterData.rooms.filter((item) => !form.buildingId || item.parentId === form.buildingId),
);

function onLocationTypeChange(): void {
  if (form.locationType === "OUTDOOR") {
    form.buildingId = "";
    form.roomId = "";
  }
}

function onBuildingChange(): void {
  form.roomId = "";
}

function onFileChange(file: any): void {
  if (file.raw && file.raw.type?.startsWith("image/")) {
    if (!files.value.some((item) => item.name === file.name && item.size === file.raw.size)) {
      files.value.push(file.raw);
    }
  }
}

function onFileRemove(file: any): void {
  files.value = files.value.filter(
    (item) => !(item.name === file.name && item.size === file.raw?.size),
  );
}

function reset(): void {
  form.description = "";
  form.ticketTypeCode = "ELEVATOR";
  form.locationType = "INDOOR";
  form.locationText = "";
  form.buildingId = "";
  form.roomId = "";
  form.addressText = "";
  fileList.value = [];
  files.value = [];
}

function submit(): void {
  if (!form.description.trim() || !form.ticketTypeCode) {
    ElMessage.warning("请填写描述和类型");
    return;
  }
  if (!form.locationType || !form.locationText.trim()) {
    ElMessage.warning("请选择室内/室外并填写位置");
    return;
  }
  if (form.locationType === "INDOOR" && (!form.buildingId || !form.roomId)) {
    ElMessage.warning("室内报修请选择楼宇和房间");
    return;
  }
  emit("submit", {
    payload: {
      description: form.description.trim(),
      ticketTypeCode: form.ticketTypeCode,
      priorityCode: "NORMAL",
      locationType: form.locationType,
      locationText: form.locationText.trim(),
      buildingId: form.locationType === "INDOOR" ? form.buildingId || null : null,
      roomId: form.locationType === "INDOOR" ? form.roomId || null : null,
      addressText: form.addressText.trim() || null,
    },
    files: files.value,
  });
}

watch(() => props.modelValue, (open) => {
  if (open) reset();
});
</script>

<style scoped>
.profile-phone {
  margin-left: 10px;
  color: #909399;
  font-size: 12px;
}
.profile-link {
  margin-left: 12px;
  font-size: 12px;
  color: #409eff;
}
.upload-plus {
  font-size: 28px;
  line-height: 1;
  color: #909399;
}
.upload-hint {
  margin-top: 6px;
  color: #909399;
  font-size: 12px;
}
</style>
