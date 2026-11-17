<template>
  <div class="page-container">
    <h2 class="page-title">知识库</h2>

    <el-card class="section-card">
      <template #header>
        <span>知识入库</span>
      </template>
      <el-form :inline="true" :model="uploadForm">
        <el-form-item label="标题">
          <el-input v-model="uploadForm.title" placeholder="知识标题" style="width: 260px" />
        </el-form-item>
        <el-form-item label="分类">
          <el-select v-model="uploadForm.categoryCode" style="width: 160px">
            <el-option v-for="item in categoryOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-upload
            :show-file-list="false"
            :http-request="uploadKnowledge"
            accept=".md,.txt"
          >
            <el-button type="primary" :loading="uploadLoading">上传 md/txt</el-button>
          </el-upload>
        </el-form-item>
      </el-form>
      <p class="hint">当前支持 md/txt；PDF/docx 后续接入。上传后同步分块、Embedding 并写入 Milvus Lite。</p>
    </el-card>

    <el-card class="section-card">
      <template #header>
        <span>文档列表</span>
        <el-button link type="primary" class="refresh" @click="loadDocs">刷新</el-button>
      </template>
      <el-table v-loading="docsLoading" :data="docs" stripe>
        <el-table-column prop="docNo" label="文档号" width="170" />
        <el-table-column prop="title" label="标题" min-width="220" />
        <el-table-column prop="categoryCode" label="分类" width="120" />
        <el-table-column prop="status" label="状态" width="110" />
        <el-table-column prop="chunkCount" label="分块数" width="90" />
        <el-table-column prop="embeddingDim" label="维度" width="80" />
        <el-table-column label="创建时间" width="180">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="downloadDoc(row)">下载</el-button>
            <el-button
              v-if="auth.permissions.includes('knowledge:manage')"
              link
              type="danger"
              @click="deleteDoc(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card class="section-card">
      <template #header>
        <span>知识检索</span>
      </template>
      <div class="search-row">
        <el-input
          v-model="searchQuery"
          placeholder="输入问题，例如：电梯困人怎么处理"
          @keyup.enter="search"
        />
        <el-button type="primary" :loading="searchLoading" @click="search">检索</el-button>
      </div>
      <div v-if="hits.length === 0 && searched" class="hint">未命中知识。</div>
      <div v-for="hit in hits" :key="hit.vectorId" class="hit-item">
        <div class="hit-head">
          <strong>{{ hit.title }}</strong>
          <el-tag size="small" type="success">{{ (hit.score * 100).toFixed(1) }}%</el-tag>
          <span class="hit-meta">{{ hit.docNo }} · chunk {{ hit.chunkNo }}</span>
        </div>
        <div class="hit-content">{{ hit.content }}</div>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { http } from "@/api/http";
import { useAuthStore } from "@/stores/auth";

interface KnowledgeDoc {
  id: string;
  docNo: string;
  title: string;
  categoryCode: string;
  status: string;
  chunkCount: number;
  embeddingModel?: string;
  embeddingDim?: number;
  createdAt?: string;
}

interface KnowledgeHit {
  docId: string;
  docNo: string;
  title: string;
  chunkNo: number;
  vectorId: string;
  score: number;
  content: string;
}

const categoryOptions = [
  { label: "电梯", value: "ELEVATOR" },
  { label: "空调", value: "HVAC" },
  { label: "水泵", value: "PUMP" },
  { label: "消防", value: "FIRE" },
  { label: "门禁", value: "ACCESS" },
  { label: "网络", value: "NETWORK" },
  { label: "通用", value: "GENERAL" },
];

const auth = useAuthStore();

const uploadForm = reactive({ title: "", categoryCode: "ELEVATOR" });
const uploadLoading = ref(false);
const docs = ref<KnowledgeDoc[]>([]);
const docsLoading = ref(false);
const searchQuery = ref("");
const searchLoading = ref(false);
const searched = ref(false);
const hits = ref<KnowledgeHit[]>([]);

function formatTime(value?: string): string {
  return value ? new Date(value).toLocaleString() : "-";
}

async function loadDocs(): Promise<void> {
  docsLoading.value = true;
  try {
    const body = (await http.get("/knowledge/docs", {
      params: { page: 1, pageSize: 50 },
    })) as any;
    if (body.code !== 0) throw new Error(body.message);
    docs.value = body.data.items || [];
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.message || error?.message || "加载知识文档失败");
  } finally {
    docsLoading.value = false;
  }
}

async function downloadDoc(row: KnowledgeDoc): Promise<void> {
  try {
    const body = (await http.get(`/knowledge/docs/${row.id}/download`)) as any;
    if (body.code !== 0) throw new Error(body.message);
    window.open(body.data.url, "_blank", "noopener");
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.message || error?.message || "下载失败");
  }
}

async function deleteDoc(row: KnowledgeDoc): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定删除知识「${row.title}」？将同时删除其向量索引，且不可恢复。`,
      "删除知识",
      { type: "warning", confirmButtonText: "删除", cancelButtonText: "取消" },
    );
  } catch {
    return;
  }
  try {
    const body = (await http.delete(`/knowledge/docs/${row.id}`)) as any;
    if (body.code !== 0) throw new Error(body.message);
    ElMessage.success("知识已删除");
    await loadDocs();
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.message || error?.message || "删除失败");
  }
}

async function uploadKnowledge(options: any): Promise<void> {
  if (!uploadForm.title.trim()) {
    ElMessage.warning("请先填写知识标题");
    options.onError?.(new Error("title required"));
    return;
  }
  if (!options.file) return;
  const form = new FormData();
  form.append("file", options.file);
  form.append("title", uploadForm.title.trim());
  form.append("categoryCode", uploadForm.categoryCode);
  uploadLoading.value = true;
  try {
    const body = (await http.post("/knowledge/docs", form)) as any;
    if (body.code !== 0) throw new Error(body.message);
    ElMessage.success(`知识入库成功：${body.data.docNo}，${body.data.chunkCount} 个分块`);
    options.onSuccess?.(body);
    uploadForm.title = "";
    await loadDocs();
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.message || error?.message || "知识入库失败");
    options.onError?.(error);
  } finally {
    uploadLoading.value = false;
  }
}

async function search(): Promise<void> {
  if (!searchQuery.value.trim()) {
    ElMessage.warning("请输入检索内容");
    return;
  }
  searchLoading.value = true;
  searched.value = true;
  try {
    const body = (await http.post("/knowledge/search", {
      query: searchQuery.value.trim(),
      topK: 5,
    })) as any;
    if (body.code !== 0) throw new Error(body.message);
    hits.value = body.data || [];
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.message || error?.message || "检索失败");
    hits.value = [];
  } finally {
    searchLoading.value = false;
  }
}

onMounted(loadDocs);
</script>

<style scoped>
.section-card {
  margin-bottom: 16px;
}
.hint {
  margin: 8px 0 0;
  color: #909399;
  font-size: 12px;
}
.refresh {
  float: right;
}
.search-row {
  display: flex;
  gap: 12px;
}
.hit-item {
  padding: 12px 0;
  border-bottom: 1px solid #ebeef5;
}
.hit-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
}
.hit-meta {
  color: #909399;
  font-size: 12px;
}
.hit-content {
  color: #606266;
  line-height: 1.6;
  white-space: pre-wrap;
}
</style>
