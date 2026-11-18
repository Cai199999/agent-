<template>
  <div class="page-container">
    <div class="page-header">
      <h2 class="page-title">工单管理</h2>
      <div class="page-actions">
        <el-date-picker
          v-model="filters.dateRange"
          type="datetimerange"
          range-separator="至"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          style="width: 340px"
        />
        <el-button v-if="can('ticket:read')" :loading="exporting" @click="exportTickets">
          导出
        </el-button>
        <el-button v-if="can('ticket:create')" type="primary" @click="openCreate">
          新建工单
        </el-button>
      </div>
    </div>

    <el-card class="filter-card">
      <el-form :inline="true" :model="filters" class="filter-form">
        <el-form-item label="关键词">
          <el-input v-model="filters.keyword" clearable placeholder="工单号 / 标题" style="width: 200px" @keyup.enter="search" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="filters.status" clearable placeholder="全部" style="width: 150px">
            <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="优先级">
          <el-select v-model="filters.priorityCode" clearable placeholder="全部" style="width: 120px">
            <el-option label="紧急" value="URGENT" />
            <el-option label="高" value="HIGH" />
            <el-option label="普通" value="NORMAL" />
            <el-option label="低" value="LOW" />
          </el-select>
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="filters.ticketTypeCode" clearable placeholder="全部" style="width: 150px">
            <el-option v-for="item in typeOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="filters.mine">只看我的</el-checkbox>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="search">查询</el-button>
          <el-button @click="resetFilters">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card>
      <el-table v-loading="loading" :data="tickets" row-key="id" stripe>
        <el-table-column prop="ticketNo" label="工单号" width="170" />
        <el-table-column prop="title" label="标题" min-width="220" show-overflow-tooltip />
        <el-table-column prop="ticketTypeCode" label="类型" width="120" />
        <el-table-column prop="priorityCode" label="优先级" width="100" />
        <el-table-column label="状态" width="130">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.statusCode)">{{ statusLabel(row.statusCode) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="assigneeId" label="处理人" width="120">
          <template #default="{ row }">{{ row.assigneeId || "-" }}</template>
        </el-table-column>
        <el-table-column label="创建时间" width="180">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="360" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button
              v-if="row.statusCode === 'PENDING' && can('ticket:assign')"
              link
              type="primary"
              @click="openAssign(row)"
            >
              派单
            </el-button>
            <el-button
              v-if="row.statusCode === 'PENDING_ACCEPT' && can('ticket:handle')"
              link
              type="success"
              @click="performAction(row, 'accept', { expectedVersion: row.version })"
            >
              接单
            </el-button>
            <el-button
              v-if="row.statusCode === 'PROCESSING' && can('ticket:handle')"
              link
              type="success"
              @click="resolve(row)"
            >
              解决
            </el-button>
            <el-button
              v-if="row.statusCode === 'PENDING_VERIFY' && can('ticket:verify')"
              link
              type="success"
              @click="performAction(row, 'verify', { expectedVersion: row.version, approved: true })"
            >
              验证通过
            </el-button>
            <el-button
              v-if="row.statusCode === 'RESOLVED' && can('ticket:close')"
              link
              type="info"
              @click="performAction(row, 'close', { expectedVersion: row.version, closeReasonCode: 'SOLVED' })"
            >
              关闭
            </el-button>
            <el-button
              v-if="['PENDING_ACCEPT', 'PROCESSING'].includes(row.statusCode) && can('ticket:handle')"
              link
              type="warning"
              @click="hold(row)"
            >
              挂起
            </el-button>
            <el-button
              v-if="row.statusCode === 'SUSPENDED' && can('ticket:handle')"
              link
              type="success"
              @click="resume(row)"
            >
              恢复
            </el-button>
            <el-button
              v-if="!['CLOSED', 'CANCELLED'].includes(row.statusCode) && can('ticket:close')"
              link
              type="warning"
              @click="finishTicket(row)"
            >
              结束
            </el-button>
            <el-button
              v-if="!['CLOSED', 'CANCELLED'].includes(row.statusCode) && can('ticket:close')"
              link
              type="danger"
              @click="cancel(row)"
            >
              取消
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        class="pagination"
        layout="total, prev, pager, next"
        :total="total"
        :page-size="pageSize"
        :current-page="page"
        @current-change="onPageChange"
      />
    </el-card>

    <TicketCreateDialog
      v-model="createVisible"
      :profile="profile"
      :master-data="masterData"
      :loading="masterDataLoading"
      :saving="creating"
      @submit="handleCreateSubmit"
    />

    <el-dialog v-model="assignVisible" title="派单" width="440px">
      <el-form label-width="80px">
        <el-form-item label="工单">{{ assignRow?.ticketNo }}</el-form-item>
        <el-form-item label="处理人" required>
          <el-select v-model="assignAssigneeId" filterable placeholder="选择处理人" style="width: 100%">
            <el-option
              v-for="user in userOptions"
              :key="user.id"
              :label="user.displayName || user.username"
              :value="user.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="assignVisible = false">取消</el-button>
        <el-button type="primary" :loading="assigning" @click="confirmAssign">确定派单</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="resolveVisible" title="提交解决" width="560px">
      <el-form label-width="100px">
        <el-form-item label="服务项目" required>
          <el-input v-model="resolveForm.serviceItemText" maxlength="200" placeholder="文字填写本次服务项目" />
        </el-form-item>
        <el-form-item label="现场情况">
          <el-input
            v-model="resolveForm.onsiteSituation"
            type="textarea"
            :rows="3"
            maxlength="1000"
            show-word-limit
            placeholder="选填：现场现象、已做处理、遗留问题"
          />
        </el-form-item>
        <el-form-item label="现场照片" required>
          <input
            ref="resolveCameraInput"
            class="hidden-file"
            type="file"
            accept="image/*"
            capture="environment"
            multiple
            @change="onResolveCameraChange"
          />
          <el-button @click="triggerResolveCamera">拍摄照片</el-button>
          <span class="upload-hint">已选 {{ resolveFiles.length }} 张（至少 1 张）</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resolveVisible = false">取消</el-button>
        <el-button type="primary" :loading="resolving" @click="submitResolve">提交解决</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="materialVisible" title="添加材料" width="520px">
      <el-form label-width="90px">
        <el-form-item label="材料名称" required>
          <el-input v-model="materialForm.materialName" maxlength="200" />
        </el-form-item>
        <el-form-item label="规格型号">
          <el-input v-model="materialForm.specification" maxlength="200" />
        </el-form-item>
        <el-form-item label="数量">
          <el-input-number v-model="materialForm.quantity" :min="0" :precision="3" />
          <el-input v-model="materialForm.unit" placeholder="单位" style="width: 100px; margin-left: 8px" />
        </el-form-item>
        <el-form-item label="金额">
          <el-input-number v-model="materialForm.amount" :min="0" :precision="2" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="materialForm.remark" type="textarea" :rows="2" maxlength="500" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="materialVisible = false">取消</el-button>
        <el-button type="primary" :loading="materialSaving" @click="saveMaterial">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="visitVisible" title="登记回访" width="520px">
      <el-form label-width="90px">
        <el-form-item label="是否回访">
          <el-switch v-model="visitForm.required" />
        </el-form-item>
        <el-form-item label="回访方式">
          <el-select v-model="visitForm.visitMethod" style="width: 100%">
            <el-option label="电话" value="PHONE" />
            <el-option label="上门" value="VISIT" />
            <el-option label="微信" value="WECHAT" />
            <el-option label="其他" value="OTHER" />
          </el-select>
        </el-form-item>
        <el-form-item label="回访时间">
          <el-date-picker v-model="visitForm.visitedAt" type="datetime" style="width: 100%" />
        </el-form-item>
        <el-form-item label="回访结果">
          <el-input v-model="visitForm.visitResult" type="textarea" :rows="3" maxlength="500" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="visitForm.status" style="width: 100%">
            <el-option label="已完成" value="DONE" />
            <el-option label="未接通" value="FAILED" />
            <el-option label="待回访" value="PENDING" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="visitForm.remark" maxlength="500" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="visitVisible = false">取消</el-button>
        <el-button type="primary" :loading="visitSaving" @click="saveVisit">保存</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="detailVisible" title="工单详情" size="50%">
      <div v-loading="detailLoading">
        <template v-if="detail">
          <div v-if="!['CLOSED', 'CANCELLED'].includes(detail.ticket.statusCode) && can('ticket:close')" class="detail-actions">
            <el-button type="warning" size="small" @click="finishTicket(detail.ticket)">结束工单</el-button>
          </div>
          <el-descriptions :column="2" border>
            <el-descriptions-item label="工单号">{{ detail.ticket.ticketNo }}</el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag :type="statusTag(detail.ticket.statusCode)">
                {{ statusLabel(detail.ticket.statusCode) }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="标题" :span="2">{{ detail.ticket.title }}</el-descriptions-item>
            <el-descriptions-item label="类型">{{ detail.ticket.ticketTypeCode }}</el-descriptions-item>
            <el-descriptions-item label="优先级">{{ detail.ticket.priorityCode }}</el-descriptions-item>
            <el-descriptions-item label="报障人">{{ detail.reporterName || detail.ticket.reporterId || "-" }}</el-descriptions-item>
            <el-descriptions-item label="处理人">{{ detail.assigneeName || detail.ticket.assigneeId || "-" }}</el-descriptions-item>
            <el-descriptions-item label="位置">{{ detail.ticket.locationText || "-" }}</el-descriptions-item>
            <el-descriptions-item label="室内/室外">{{ locationTypeLabel(detail.ticket.locationType) }}</el-descriptions-item>
            <el-descriptions-item label="项目">{{ masterName(masterData.projects, detail.ticket.projectId) }}</el-descriptions-item>
            <el-descriptions-item label="楼宇">{{ masterName(masterData.buildings, detail.ticket.buildingId) }}</el-descriptions-item>
            <el-descriptions-item label="房间">{{ masterName(masterData.rooms, detail.ticket.roomId) }}</el-descriptions-item>
            <el-descriptions-item label="客户">{{ masterName(masterData.customers, detail.ticket.customerId) }}</el-descriptions-item>
            <el-descriptions-item label="服务项目">{{ detail.ticket.serviceItemText || masterName(masterData.serviceItems, detail.ticket.serviceItemId) }}</el-descriptions-item>
            <el-descriptions-item label="设备">{{ masterName(masterData.equipment, detail.ticket.equipmentId) }}</el-descriptions-item>
            <el-descriptions-item label="当前节点">{{ nodeLabel(detail.ticket.currentNodeCode) }}</el-descriptions-item>
            <el-descriptions-item label="预约时间">{{ formatTime(detail.ticket.appointmentAt) || "-" }}</el-descriptions-item>
            <el-descriptions-item label="收单时间">{{ formatTime(detail.ticket.receivedAt) || "-" }}</el-descriptions-item>
            <el-descriptions-item label="到场时间">{{ formatTime(detail.ticket.arrivedAt) || "-" }}</el-descriptions-item>
            <el-descriptions-item label="联系人">{{ detail.ticket.contactName || "-" }}</el-descriptions-item>
            <el-descriptions-item label="联系电话">{{ detail.ticket.contactPhoneMask || "-" }}</el-descriptions-item>
            <el-descriptions-item label="户内/公区">{{ areaLabel(detail.ticket.indoorPublicArea) }}</el-descriptions-item>
            <el-descriptions-item label="现场情况" :span="2">{{ detail.ticket.onsiteSituation || "-" }}</el-descriptions-item>
            <el-descriptions-item label="详细地址" :span="2">{{ detail.ticket.addressText || "-" }}</el-descriptions-item>
            <el-descriptions-item label="挂起累计">{{ detail.holdMinutes || 0 }} 分钟</el-descriptions-item>
            <el-descriptions-item label="首次响应到期">
              {{ formatTime(detail.sla?.responseDueAt) }}
              <el-tag v-if="detail.sla?.responseBreached" size="small" type="danger">已超时</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="解决到期">
              {{ formatTime(detail.sla?.resolveDueAt || detail.ticket.slaResolveDueAt) }}
              <el-tag v-if="detail.sla?.resolveBreached" size="small" type="danger">已超时</el-tag>
            </el-descriptions-item>
            <el-descriptions-item v-if="detail.holdReason" label="挂起原因" :span="2">{{ detail.holdReason }}</el-descriptions-item>
            <el-descriptions-item label="描述" :span="2">{{ detail.description || "-" }}</el-descriptions-item>
          </el-descriptions>

          <el-divider content-position="left">AI 初步建议</el-divider>
          <div class="ai-analysis">
            <div v-if="!aiAnalysis" class="analysis-empty">
              <el-button type="primary" :loading="analysisRunning" @click="runAnalysis">开始 AI 分析</el-button>
            </div>
            <template v-else>
              <div class="analysis-head">
                <el-tag :type="riskTag(aiAnalysis.riskLevel)">风险 {{ riskLabel(aiAnalysis.riskLevel) }}</el-tag>
                <el-button link type="primary" @click="openRecommendedPlan">查看推荐方案 →</el-button>
                <el-button link :loading="analysisRunning" @click="runAnalysis">重新分析</el-button>
              </div>
              <p class="analysis-summary">{{ aiAnalysis.summary }}</p>
              <div v-if="aiAnalysis.causes?.length" class="analysis-block">
                <strong>可能原因</strong>
                <ul>
                  <li v-for="(item, idx) in aiAnalysis.causes" :key="idx">{{ item }}</li>
                </ul>
              </div>
              <div v-if="aiAnalysis.actions?.length" class="analysis-block">
                <strong>建议动作</strong>
                <ol>
                  <li v-for="(item, idx) in aiAnalysis.actions" :key="idx">{{ item }}</li>
                </ol>
              </div>
              <div v-if="aiAnalysis.citations?.length" class="analysis-citations">
                <strong>知识依据</strong>
                <el-tag
                  v-for="citation in aiAnalysis.citations"
                  :key="citation.docId"
                  size="small"
                  effect="plain"
                  @click="downloadKnowledge(citation.docId)"
                >
                  {{ citation.title }}
                  <span v-if="citation.score != null"> {{ Math.round(citation.score * 100) }}%</span>
                </el-tag>
              </div>
            </template>
          </div>

          <el-divider content-position="left">使用材料</el-divider>
          <div class="extra-list">
            <el-empty v-if="!materials.length" description="暂无材料记录" :image-size="40" />
            <div v-for="item in materials" :key="item.id" class="extra-row">
              <span>{{ item.materialName }}</span>
              <span class="muted">{{ item.specification || "" }}</span>
              <span>{{ item.quantity || 0 }} {{ item.unit || "" }}</span>
              <span v-if="item.amount != null">¥{{ item.amount }}</span>
            </div>
            <el-button
              v-if="can('ticket:handle')"
              size="small"
              type="primary"
              plain
              @click="openMaterial"
            >
              添加材料
            </el-button>
          </div>

          <el-divider content-position="left">回访记录</el-divider>
          <div class="extra-list">
            <el-empty v-if="!visits.length" description="暂无回访记录" :image-size="40" />
            <div v-for="item in visits" :key="item.id" class="extra-row">
              <el-tag size="small" :type="item.status === 'DONE' ? 'success' : 'info'">{{ item.status }}</el-tag>
              <span>{{ item.visitMethod || "-" }}</span>
              <span>{{ item.visitorName || item.visitorId || "-" }}</span>
              <span>{{ formatTime(item.visitedAt) }}</span>
              <span class="muted">{{ item.visitResult || item.remark || "" }}</span>
            </div>
            <el-button
              v-if="detail.ticket.statusCode === 'CLOSED' && can('ticket:close')"
              size="small"
              type="primary"
              plain
              @click="openVisit"
            >
              登记回访
            </el-button>
          </div>

          <el-divider content-position="left">相似历史工单</el-divider>
          <div v-loading="similarLoading" class="similar-list">
            <el-empty v-if="!similarTickets.length" description="暂无相似工单" :image-size="48" />
            <div v-for="similar in similarTickets" :key="similar.ticketId" class="similar-item">
              <span class="similar-no">{{ similar.ticketNo }}</span>
              <span class="similar-title">{{ similar.title }}</span>
              <el-tag size="small" :type="statusTag(similar.statusCode)">
                {{ statusLabel(similar.statusCode) }}
              </el-tag>
              <span class="similar-score">{{ Math.round((similar.score || 0) * 100) }}%</span>
            </div>
          </div>

          <h4>事件时间线</h4>
          <el-timeline>
            <el-timeline-item
              v-for="event in detail.events"
              :key="event.eventId"
              :timestamp="formatTime(event.createdAt)"
            >
              {{ event.eventType }}：{{ event.fromStatus || "-" }} → {{ event.toStatus || "-" }}
              <span v-if="event.reason">（{{ event.reason }}）</span>
            </el-timeline-item>
          </el-timeline>

          <el-divider content-position="left">附件</el-divider>
          <div v-if="attachments.length === 0" class="empty-comments">暂无附件</div>
          <div v-for="att in attachments" :key="att.id" class="attachment-item">
            <el-tag v-if="att.attachmentType" size="small" effect="plain">
              {{ att.attachmentType === "ONSITE" ? "现场照片" : att.attachmentType === "USER" ? "报修图片" : "附件" }}
            </el-tag>
            <a :href="att.url || '#'" target="_blank" rel="noopener">{{ att.originalName }}</a>
            <span class="attachment-meta">
              {{ formatSize(att.sizeBytes) }} · {{ formatTime(att.createdAt) }}
            </span>
          </div>
          <el-upload
            :show-file-list="false"
            :http-request="uploadAttachment"
            accept=".pdf,.docx,.md,.png,.jpg"
          >
            <el-button :loading="attachmentLoading">上传附件</el-button>
          </el-upload>

          <el-divider content-position="left">评论</el-divider>
          <div v-if="comments.length === 0" class="empty-comments">暂无评论</div>
          <div v-for="comment in comments" :key="comment.id" class="comment-item">
            <div class="comment-meta">
              <span>#{{ comment.authorId }}</span>
              <el-tag size="small" :type="comment.visibility === 'INTERNAL' ? 'warning' : 'info'">
                {{ comment.visibility === "INTERNAL" ? "内部备注" : "公开" }}
              </el-tag>
              <span>{{ formatTime(comment.createdAt) }}</span>
            </div>
            <div class="comment-content">{{ comment.content }}</div>
          </div>
          <div class="comment-editor">
            <el-input v-model="commentText" type="textarea" :rows="2" placeholder="输入评论..." />
            <div class="comment-actions">
              <el-checkbox v-model="internalNote">内部备注</el-checkbox>
              <el-button type="primary" :loading="commentLoading" @click="addComment">发送</el-button>
            </div>
          </div>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { http, toErrorMessage } from "@/api/http";
import { getMasterDataOptions, type MasterDataOptions } from "@/api/masterData";
import { getProfile, type UserProfile } from "@/api/profile";
import TicketCreateDialog from "./components/TicketCreateDialog.vue";
import {
  addMaterial,
  addVisit,
  listMaterials,
  listVisits,
  type TicketMaterial,
  type TicketVisit,
} from "@/api/ticketExtras";
import { useAuthStore } from "@/stores/auth";

interface TicketSummary {
  id: string;
  ticketNo: string;
  title: string;
  statusCode: string;
  priorityCode: string;
  ticketTypeCode: string;
  reporterId?: string;
  assigneeId?: string;
  customerId?: string | null;
  roomId?: string | null;
  equipmentId?: string | null;
  locationText?: string;
  createdAt?: string;
  slaResolveDueAt?: string;
  version: number;
  projectId?: string | null;
  buildingId?: string | null;
  serviceItemId?: string | null;
  currentNodeCode?: string | null;
  appointmentAt?: string | null;
  receivedAt?: string | null;
  arrivedAt?: string | null;
  taskDueAt?: string | null;
  contactName?: string | null;
  contactPhoneMask?: string | null;
  addressText?: string | null;
  indoorPublicArea?: string | null;
  locationType?: string | null;
  serviceItemText?: string | null;
  onsiteSituation?: string | null;
}

interface TicketEventView {
  eventId: string;
  eventType: string;
  fromStatus?: string;
  toStatus?: string;
  reason?: string;
  createdAt?: string;
}

interface TicketSlaView {
  responseDueAt?: string;
  resolveDueAt?: string;
  responseBreached?: boolean;
  resolveBreached?: boolean;
  firstResponseAt?: string;
  acceptedAt?: string;
  resolvedAt?: string;
  closedAt?: string;
}

interface TicketDetail {
  ticket: TicketSummary;
  description?: string;
  events: TicketEventView[];
  reporterName?: string;
  assigneeName?: string;
  holdMinutes?: number;
  holdReason?: string;
  sla?: TicketSlaView;
}

interface AnalysisCitation {
  docId: string;
  docNo?: string;
  title: string;
  score?: number;
}

interface TicketAiAnalysis {
  id: string;
  ticketId: string;
  summary: string;
  causes: string[];
  actions: string[];
  riskLevel: string;
  citations: AnalysisCitation[];
  model?: string;
  createdAt?: string;
}

interface SimilarTicket {
  ticketId: string;
  ticketNo: string;
  title: string;
  statusCode: string;
  priorityCode?: string;
  ticketTypeCode?: string;
  score: number;
}

interface UserOption {
  id: string;
  username: string;
  displayName: string;
}

interface TicketComment {
  id: string;
  ticketId: string;
  parentId?: string;
  authorId: string;
  visibility: string;
  content: string;
  createdAt?: string;
}

interface Attachment {
  id: string;
  fileId: string;
  originalName: string;
  contentType?: string;
  sizeBytes: number;
  uploadedBy?: string;
  attachmentType?: string | null;
  createdAt?: string;
  url?: string;
}

const auth = useAuthStore();
const loading = ref(false);
const creating = ref(false);
const exporting = ref(false);
const tickets = ref<TicketSummary[]>([]);
const total = ref(0);
const page = ref(1);
const pageSize = ref(20);
const createVisible = ref(false);
const idempotencyKey = ref("");
const detailVisible = ref(false);
const detailLoading = ref(false);
const detail = ref<TicketDetail | null>(null);
const comments = ref<TicketComment[]>([]);
const commentText = ref("");
const internalNote = ref(false);
const commentLoading = ref(false);
const attachments = ref<Attachment[]>([]);
const attachmentLoading = ref(false);
const userOptions = ref<UserOption[]>([]);
const similarTickets = ref<SimilarTicket[]>([]);
const similarLoading = ref(false);
const aiAnalysis = ref<TicketAiAnalysis | null>(null);
const analysisRunning = ref(false);
const route = useRoute();
const router = useRouter();
const assignVisible = ref(false);
const assignRow = ref<TicketSummary | null>(null);
const assignAssigneeId = ref("");
const assigning = ref(false);
const resolveVisible = ref(false);
const resolveRow = ref<TicketSummary | null>(null);
const resolving = ref(false);
const resolveCameraInput = ref<HTMLInputElement | null>(null);
const resolveFiles = ref<File[]>([]);
const resolveForm = reactive({ serviceItemText: "", onsiteSituation: "" });
const materials = ref<TicketMaterial[]>([]);
const visits = ref<TicketVisit[]>([]);
const materialVisible = ref(false);
const materialSaving = ref(false);
const visitVisible = ref(false);
const visitSaving = ref(false);
const materialForm = reactive({
  materialName: "",
  specification: "",
  quantity: 1,
  unit: "",
  amount: null as number | null,
  remark: "",
});
const visitForm = reactive({
  required: true,
  visitMethod: "PHONE",
  visitedAt: null as Date | null,
  visitResult: "",
  status: "DONE",
  remark: "",
});

const filters = reactive({
  keyword: "",
  status: "",
  priorityCode: "",
  ticketTypeCode: "",
  mine: false,
  dateRange: [] as Date[] | null,
});

const statusOptions = [
  { label: "待受理", value: "PENDING" },
  { label: "待接单", value: "PENDING_ACCEPT" },
  { label: "处理中", value: "PROCESSING" },
  { label: "挂起", value: "SUSPENDED" },
  { label: "待验证", value: "PENDING_VERIFY" },
  { label: "已解决", value: "RESOLVED" },
  { label: "已关闭", value: "CLOSED" },
  { label: "已取消", value: "CANCELLED" },
];

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

const profile = ref<UserProfile>({});

const masterData = ref<MasterDataOptions>({
  projects: [],
  buildings: [],
  rooms: [],
  customers: [],
  equipment: [],
  serviceItems: [],
});
const masterDataLoading = ref(false);

async function loadMasterData(): Promise<void> {
  if (masterData.value.projects.length) return;
  masterDataLoading.value = true;
  try {
    masterData.value = await getMasterDataOptions();
  } catch (error: any) {
    ElMessage.error(toErrorMessage(error, "加载主数据失败"));
  } finally {
    masterDataLoading.value = false;
  }
}

function can(permission: string): boolean {
  return auth.permissions.includes(permission);
}

function masterName(options: { id: string; name: string }[], id?: string | null): string {
  if (!id) return "-";
  return options.find((item) => item.id === id)?.name || id;
}

function nodeLabel(code?: string | null): string {
  return (
    {
      ACCEPT: "受理",
      DISPATCH: "派单",
      HANDLE: "处理",
      VERIFY: "验证",
      CLOSE: "关闭",
    } as Record<string, string>
  )[code || ""] || code || "-";
}

function areaLabel(code?: string | null): string {
  return code === "INDOOR" ? "户内" : code === "PUBLIC" ? "公区" : "-";
}

function locationTypeLabel(code?: string | null): string {
  return code === "INDOOR" ? "室内" : code === "OUTDOOR" ? "室外" : "-";
}

function statusLabel(status: string): string {
  return (
    {
      PENDING: "待受理",
      PENDING_ASSIGN: "待派单",
      PENDING_ACCEPT: "待接单",
      PROCESSING: "处理中",
      SUSPENDED: "挂起",
      PENDING_VERIFY: "待验证",
      RESOLVED: "已解决",
      CLOSED: "已关闭",
      CANCELLED: "已取消",
    }[status] || status
  );
}

function statusTag(status: string): "success" | "warning" | "info" | "danger" | "primary" {
  if (["RESOLVED", "CLOSED"].includes(status)) return "success";
  if (["PROCESSING"].includes(status)) return "primary";
  if (["PENDING_ACCEPT", "PENDING_VERIFY", "SUSPENDED", "PENDING_ASSIGN"].includes(status)) return "warning";
  if (["CANCELLED"].includes(status)) return "danger";
  return "info";
}

function formatTime(value?: string | null): string {
  if (!value) return "-";
  return new Date(value).toLocaleString();
}

function formatSize(value?: number): string {
  if (!value) return "-";
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  return `${(value / 1024 / 1024).toFixed(1)} MB`;
}

function exportFileName(): string {
  const now = new Date();
  const pad = (value: number) => String(value).padStart(2, "0");
  return `tickets-${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}`
    + `-${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}.xlsx`;
}

async function exportTickets(): Promise<void> {
  exporting.value = true;
  try {
    const blob = (await http.get("/tickets/export", {
      params: {
        status: filters.status || undefined,
        keyword: filters.keyword || undefined,
        priorityCode: filters.priorityCode || undefined,
        ticketTypeCode: filters.ticketTypeCode || undefined,
        mine: filters.mine,
        startTime: filters.dateRange?.[0]?.toISOString(),
        endTime: filters.dateRange?.[1]?.toISOString(),
      },
      responseType: "blob",
    })) as unknown as Blob;
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = exportFileName();
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
    ElMessage.success("导出完成");
  } catch (error: any) {
    ElMessage.error(toErrorMessage(error, "导出失败"));
  } finally {
    exporting.value = false;
  }
}

async function load(): Promise<void> {
  loading.value = true;
  try {
    const body = (await http.get("/tickets", {
      params: {
        page: page.value,
        pageSize: pageSize.value,
        status: filters.status || undefined,
        keyword: filters.keyword || undefined,
        priorityCode: filters.priorityCode || undefined,
        ticketTypeCode: filters.ticketTypeCode || undefined,
        mine: filters.mine,
        startTime: filters.dateRange?.[0]?.toISOString(),
        endTime: filters.dateRange?.[1]?.toISOString(),
      },
    })) as any;
    if (body.code !== 0) throw new Error(body.message);
    tickets.value = body.data.items || [];
    total.value = body.data.total || 0;
  } catch (error: any) {
    ElMessage.error(toErrorMessage(error, "加载工单失败"));
  } finally {
    loading.value = false;
  }
}

function onPageChange(value: number): void {
  page.value = value;
  void load();
}

function search(): void {
  page.value = 1;
  void load();
}

function resetFilters(): void {
  filters.keyword = "";
  filters.status = "";
  filters.priorityCode = "";
  filters.ticketTypeCode = "";
  filters.mine = false;
  filters.dateRange = [];
  page.value = 1;
  void load();
}

async function loadUserOptions(): Promise<void> {
  if (userOptions.value.length) return;
  const body = (await http.get("/users/options")) as any;
  if (body.code === 0) {
    userOptions.value = body.data || [];
  }
}

async function openAssign(row: TicketSummary): Promise<void> {
  assignRow.value = row;
  assignAssigneeId.value = row.assigneeId || "";
  assignVisible.value = true;
  try {
    await loadUserOptions();
  } catch (error: any) {
    ElMessage.error(toErrorMessage(error, "加载用户列表失败"));
  }
}

async function confirmAssign(): Promise<void> {
  if (!assignRow.value || !assignAssigneeId.value) {
    ElMessage.warning("请选择处理人");
    return;
  }
  assigning.value = true;
  try {
    await performAction(assignRow.value, "assign", {
      expectedVersion: assignRow.value.version,
      assigneeId: assignAssigneeId.value,
    });
    assignVisible.value = false;
  } finally {
    assigning.value = false;
  }
}

async function hold(row: TicketSummary): Promise<void> {
  try {
    const result = await ElMessageBox.prompt("请输入挂起原因", "挂起工单", {
      inputType: "textarea",
      inputPlaceholder: "例如：等待配件到货",
    });
    await performAction(row, "hold", {
      expectedVersion: row.version,
      reason: result.value,
    });
  } catch {
    // 用户取消
  }
}

async function finishTicket(row: TicketSummary): Promise<void> {
  try {
    const result = await ElMessageBox.prompt("工单将直接结束并关闭，可填写结束说明", "结束工单", {
      inputType: "textarea",
      inputPlaceholder: "例如：现场已恢复，问题解决",
      confirmButtonText: "结束工单",
      cancelButtonText: "取消",
    });
    await performAction(row, "finish", {
      expectedVersion: row.version,
      reason: result.value,
    });
  } catch {
    // 用户取消
  }
}

async function resume(row: TicketSummary): Promise<void> {
  try {
    await ElMessageBox.confirm("恢复后将继续处理，并顺延 SLA 到期时间。", "恢复工单", {
      type: "warning",
      confirmButtonText: "恢复",
      cancelButtonText: "取消",
    });
  } catch {
    return;
  }
  await performAction(row, "resume", {
    expectedVersion: row.version,
    reason: "恢复处理",
  });
}

async function loadProfile(): Promise<void> {
  try {
    profile.value = await getProfile();
  } catch (error: any) {
    ElMessage.error(toErrorMessage(error, "加载个人资料失败"));
  }
}

function openCreate(): void {
  idempotencyKey.value = crypto.randomUUID();
  void loadProfile();
  void loadMasterData();
  createVisible.value = true;
}

async function deleteAttachments(ticketId: string, attachmentIds: string[]): Promise<void> {
  for (const attachmentId of attachmentIds) {
    try {
      await http.delete(`/tickets/${ticketId}/attachments/${attachmentId}`);
    } catch (error: any) {
      ElMessage.warning(toErrorMessage(error, "临时图片清理失败，请手动删除"));
    }
  }
}

async function uploadTicketFiles(
  ticketId: string,
  files: File[],
  type = "GENERAL",
  strict = false,
): Promise<string[]> {
  const uploaded: string[] = [];
  for (const file of files) {
    const form = new FormData();
    form.append("file", file);
    try {
      const body = (await http.post(`/tickets/${ticketId}/attachments?type=${type}`, form)) as any;
      if (body.code !== 0) throw new Error(body.message);
      if (body.data?.id) uploaded.push(String(body.data.id));
    } catch (error: any) {
      if (strict) {
        await deleteAttachments(ticketId, uploaded);
        throw error;
      }
      ElMessage.warning(toErrorMessage(error, `图片 ${file.name} 上传失败`));
    }
  }
  return uploaded;
}

async function handleCreateSubmit(value: { payload: Record<string, unknown>; files: File[] }): Promise<void> {
  if (!idempotencyKey.value) {
    idempotencyKey.value = crypto.randomUUID();
  }
  creating.value = true;
  try {
    const body = (await http.post("/tickets", value.payload, {
      headers: { "Idempotency-Key": idempotencyKey.value },
    })) as any;
    if (body.code !== 0) throw new Error(body.message);
    const ticketId = body.data.ticket.id;
    if (value.files.length) {
      await uploadTicketFiles(ticketId, value.files, "USER");
    }
    ElMessage.success(`工单已创建：${body.data.ticket.ticketNo}`);
    createVisible.value = false;
    idempotencyKey.value = "";
    await load();
  } catch (error: any) {
    ElMessage.error(toErrorMessage(error, "创建失败"));
  } finally {
    creating.value = false;
  }
}

async function performAction(row: TicketSummary, action: string, payload: Record<string, unknown>): Promise<void> {
  try {
    const body = (await http.post(`/tickets/${row.id}/${action}`, payload)) as any;
    if (body.code !== 0) throw new Error(body.message);
    ElMessage.success("操作成功");
    await load();
    if (detailVisible.value && detail.value?.ticket.id === row.id) {
      await openDetail(row);
    }
  } catch (error: any) {
    ElMessage.error(toErrorMessage(error, "操作失败"));
  }
}

function resolve(row: TicketSummary): void {
  resolveRow.value = row;
  resolveForm.serviceItemText = "";
  resolveForm.onsiteSituation = "";
  resolveFiles.value = [];
  resolveVisible.value = true;
}

function triggerResolveCamera(): void {
  resolveCameraInput.value?.click();
}

function onResolveCameraChange(event: Event): void {
  const input = event.target as HTMLInputElement;
  for (const file of Array.from(input.files || [])) {
    if (file.type.startsWith("image/")) {
      resolveFiles.value.push(file);
    }
  }
  input.value = "";
}

async function submitResolve(): Promise<void> {
  if (!resolveRow.value) return;
  if (!resolveForm.serviceItemText.trim()) {
    ElMessage.warning("请填写服务项目");
    return;
  }
  if (!resolveFiles.value.length) {
    ElMessage.warning("请至少拍摄一张现场照片");
    return;
  }
  resolving.value = true;
  const row = resolveRow.value;
  let uploaded: string[] = [];
  try {
    uploaded = await uploadTicketFiles(row.id, resolveFiles.value, "ONSITE", true);
    const body = (await http.post(`/tickets/${row.id}/resolve`, {
      expectedVersion: row.version,
      serviceItemText: resolveForm.serviceItemText.trim(),
      onsiteSituation: resolveForm.onsiteSituation.trim() || null,
      reason: resolveForm.onsiteSituation.trim() || "提交解决",
    })) as any;
    if (body.code !== 0) throw new Error(body.message);
    ElMessage.success("已提交解决");
    resolveVisible.value = false;
    await load();
    if (detailVisible.value && detail.value?.ticket.id === row.id) {
      await openDetail(row);
    }
  } catch (error: any) {
    if (uploaded.length) {
      await deleteAttachments(row.id, uploaded);
    }
    ElMessage.error(toErrorMessage(error, "提交解决失败"));
  } finally {
    resolving.value = false;
  }
}

function openMaterial(): void {
  materialForm.materialName = "";
  materialForm.specification = "";
  materialForm.quantity = 1;
  materialForm.unit = "";
  materialForm.amount = null;
  materialForm.remark = "";
  materialVisible.value = true;
}

async function saveMaterial(): Promise<void> {
  if (!detail.value || !materialForm.materialName.trim()) {
    ElMessage.warning("请填写材料名称");
    return;
  }
  materialSaving.value = true;
  try {
    await addMaterial(detail.value.ticket.id, {
      materialName: materialForm.materialName.trim(),
      specification: materialForm.specification.trim() || null,
      quantity: materialForm.quantity,
      unit: materialForm.unit.trim() || null,
      amount: materialForm.amount,
      remark: materialForm.remark.trim() || null,
    });
    materials.value = await listMaterials(detail.value.ticket.id);
    materialVisible.value = false;
    ElMessage.success("材料已记录");
  } catch (error: any) {
    ElMessage.error(toErrorMessage(error, "保存材料失败"));
  } finally {
    materialSaving.value = false;
  }
}

function openVisit(): void {
  visitForm.required = true;
  visitForm.visitMethod = "PHONE";
  visitForm.visitedAt = new Date();
  visitForm.visitResult = "";
  visitForm.status = "DONE";
  visitForm.remark = "";
  visitVisible.value = true;
}

async function saveVisit(): Promise<void> {
  if (!detail.value) return;
  visitSaving.value = true;
  try {
    await addVisit(detail.value.ticket.id, {
      required: visitForm.required,
      visitMethod: visitForm.visitMethod,
      visitedAt: visitForm.visitedAt?.toISOString() || null,
      visitResult: visitForm.visitResult.trim() || null,
      status: visitForm.status,
      remark: visitForm.remark.trim() || null,
    });
    visits.value = await listVisits(detail.value.ticket.id);
    visitVisible.value = false;
    ElMessage.success("回访记录已保存");
  } catch (error: any) {
    ElMessage.error(toErrorMessage(error, "保存回访失败"));
  } finally {
    visitSaving.value = false;
  }
}

async function cancel(row: TicketSummary): Promise<void> {
  try {
    const result = await ElMessageBox.prompt("请输入取消原因", "取消工单", {
      inputType: "textarea",
    });
    await performAction(row, "cancel", {
      expectedVersion: row.version,
      cancelReason: result.value,
      reason: result.value,
    });
  } catch {
    // 用户取消
  }
}

async function openDetail(row: TicketSummary): Promise<void> {
  void loadMasterData();
  detailVisible.value = true;
  detailLoading.value = true;
  similarTickets.value = [];
  similarLoading.value = true;
  aiAnalysis.value = null;
  commentText.value = "";
  internalNote.value = false;
  try {
    const [detailBody, commentsBody, attachmentsBody, similarBody, analysisBody,
      materialsBody, visitsBody] = (await Promise.all([
      http.get(`/tickets/${row.id}`),
      http.get(`/tickets/${row.id}/comments`),
      http.get(`/tickets/${row.id}/attachments`),
      http.get(`/tickets/${row.id}/similar`, { params: { topK: 5 } }).catch(() => null),
      http.get(`/tickets/${row.id}/ai-analysis`).catch(() => null),
      http.get(`/tickets/${row.id}/materials`).catch(() => null),
      http.get(`/tickets/${row.id}/visits`).catch(() => null),
    ])) as any[];
    if (detailBody.code !== 0) throw new Error(detailBody.message);
    if (commentsBody.code !== 0) throw new Error(commentsBody.message);
    if (attachmentsBody.code !== 0) throw new Error(attachmentsBody.message);
    detail.value = detailBody.data;
    comments.value = commentsBody.data || [];
    attachments.value = attachmentsBody.data || [];
    similarTickets.value = similarBody?.code === 0 ? (similarBody.data || []) : [];
    aiAnalysis.value = analysisBody?.code === 0 ? (analysisBody.data || null) : null;
    materials.value = materialsBody?.code === 0 ? (materialsBody.data || []) : [];
    visits.value = visitsBody?.code === 0 ? (visitsBody.data || []) : [];
  } catch (error: any) {
    ElMessage.error(toErrorMessage(error, "加载详情失败"));
  } finally {
    detailLoading.value = false;
    similarLoading.value = false;
  }
}

async function runAnalysis(): Promise<void> {
  if (!detail.value) return;
  analysisRunning.value = true;
  try {
    const body = (await http.post(`/tickets/${detail.value.ticket.id}/ai-analysis`)) as any;
    if (body.code !== 0) throw new Error(body.message);
    aiAnalysis.value = body.data;
    ElMessage.success("AI 分析完成");
  } catch (error: any) {
    ElMessage.error(toErrorMessage(error, "AI 分析失败"));
  } finally {
    analysisRunning.value = false;
  }
}

function riskTag(risk?: string): "success" | "warning" | "danger" | "info" {
  if (risk === "HIGH") return "danger";
  if (risk === "MEDIUM") return "warning";
  if (risk === "LOW") return "success";
  return "info";
}

function riskLabel(risk?: string): string {
  return { HIGH: "高", MEDIUM: "中", LOW: "低" }[risk || ""] || risk || "-";
}

async function downloadKnowledge(docId: string): Promise<void> {
  try {
    const body = (await http.get(`/knowledge/docs/${docId}/download`)) as any;
    if (body.code !== 0) throw new Error(body.message);
    window.open(body.data.url, "_blank", "noopener");
  } catch (error: any) {
    ElMessage.error(toErrorMessage(error, "知识下载失败"));
  }
}

function openRecommendedPlan(): void {
  if (!detail.value) return;
  const prompt = `请给出工单 ${detail.value.ticket.ticketNo}《${detail.value.ticket.title}》的详细处置方案`;
  void router.push({ path: "/chat", query: { prompt } });
}

async function loadAttachments(): Promise<void> {
  if (!detail.value) return;
  const body = (await http.get(`/tickets/${detail.value.ticket.id}/attachments`)) as any;
  if (body.code === 0) {
    attachments.value = body.data || [];
  }
}

async function uploadAttachment(options: any): Promise<void> {
  if (!detail.value || !options.file) return;
  const form = new FormData();
  form.append("file", options.file);
  attachmentLoading.value = true;
  try {
    const body = (await http.post(
      `/tickets/${detail.value.ticket.id}/attachments`,
      form,
    )) as any;
    if (body.code !== 0) throw new Error(body.message);
    ElMessage.success("附件已上传");
    options.onSuccess?.(body);
    await loadAttachments();
  } catch (error: any) {
    ElMessage.error(toErrorMessage(error, "上传失败"));
    options.onError?.(error);
  } finally {
    attachmentLoading.value = false;
  }
}

async function addComment(): Promise<void> {
  if (!detail.value || !commentText.value.trim()) {
    ElMessage.warning("请输入评论内容");
    return;
  }
  commentLoading.value = true;
  try {
    const body = (await http.post(`/tickets/${detail.value.ticket.id}/comments`, {
      content: commentText.value.trim(),
      visibility: internalNote.value ? "INTERNAL" : "PUBLIC",
    })) as any;
    if (body.code !== 0) throw new Error(body.message);
    commentText.value = "";
    internalNote.value = false;
    ElMessage.success("评论已添加");
    const commentsBody = (await http.get(
      `/tickets/${detail.value.ticket.id}/comments`,
    )) as any;
    if (commentsBody.code === 0) {
      comments.value = commentsBody.data || [];
    }
  } catch (error: any) {
    ElMessage.error(toErrorMessage(error, "添加评论失败"));
  } finally {
    commentLoading.value = false;
  }
}

onMounted(() => {
  void load();
  void loadMasterData();
  void loadProfile();
  if (route.query.create === "1") {
    openCreate();
    void router.replace({ path: "/tickets" });
  }
});
</script>

<style scoped>
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.page-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
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
.hidden-file {
  display: none;
}
.extra-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 8px;
}
.extra-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  font-size: 13px;
  color: #303133;
}
.muted {
  color: #909399;
}
.similar-list {
  min-height: 60px;
}
.similar-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 0;
  border-bottom: 1px dashed #ebeef5;
  font-size: 13px;
}
.similar-no {
  color: #909399;
  flex: none;
}
.similar-title {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.similar-score {
  color: #67c23a;
  flex: none;
}
.ai-analysis {
  padding: 4px 0;
}
.analysis-empty {
  padding: 8px 0;
}
.analysis-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}
.analysis-summary {
  margin: 0 0 8px;
  color: #303133;
  line-height: 1.6;
}
.analysis-block {
  margin-bottom: 8px;
  font-size: 13px;
}
.analysis-block ul,
.analysis-block ol {
  margin: 4px 0 0;
  padding-left: 20px;
}
.analysis-citations {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}
.analysis-citations .el-tag {
  cursor: pointer;
}
.detail-actions {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 10px;
}
.filter-card {
  margin-bottom: 16px;
}
.filter-form :deep(.el-form-item) {
  margin-bottom: 0;
}
.pagination {
  margin-top: 16px;
  justify-content: flex-end;
}
h4 {
  margin: 20px 0 12px;
}
.empty-comments {
  color: #909399;
  font-size: 13px;
  padding: 8px 0;
}
.attachment-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 6px 0;
}
.attachment-meta {
  color: #909399;
  font-size: 12px;
}
.comment-item {
  padding: 10px 0;
  border-bottom: 1px solid #ebeef5;
}
.comment-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #909399;
  font-size: 12px;
  margin-bottom: 6px;
}
.comment-content {
  white-space: pre-wrap;
  line-height: 1.6;
}
.comment-editor {
  margin-top: 16px;
}
.comment-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 8px;
}
</style>
