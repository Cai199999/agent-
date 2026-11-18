<template>
  <div class="chat-page">
    <aside class="session-panel">
      <div class="panel-head">
        <span>会话</span>
        <el-button size="small" type="primary" @click="startNewSession">新建</el-button>
      </div>
      <div v-loading="sessionsLoading" class="session-list">
        <div
          v-for="item in sessions"
          :key="item.id"
          :class="['session-item', { active: item.id === currentSessionId }]"
          @click="selectSession(item.id)"
        >
          <div class="session-title">{{ item.title || item.sessionNo }}</div>
          <div class="session-meta">{{ formatTime(item.lastMessageAt || item.createdAt) }}</div>
          <el-icon class="session-del" title="归档会话" @click.stop="removeSession(item.id)">
            <Delete />
          </el-icon>
        </div>
        <el-empty v-if="!sessions.length" description="暂无会话" :image-size="60" />
      </div>
    </aside>

    <section class="chat-panel">
      <div class="chat-head">
        <div>
          <strong>{{ currentSession?.title || "AI 助手" }}</strong>
          <span class="muted session-no">{{ currentSession?.sessionNo || "" }}</span>
        </div>
        <div class="chat-head-actions">
          <el-button size="small" plain @click="goCreateTicket">表单报修</el-button>
          <el-tag v-if="currentSession" size="small" :type="currentSession.status === 'ACTIVE' ? 'success' : 'info'">
            {{ currentSession.status }}
          </el-tag>
        </div>
      </div>

      <el-alert v-if="aiDegraded" type="warning" :closable="false" show-icon class="ai-degraded">
        <template #title>
          AI 服务暂不可用，已自动降级；你仍可
          <el-button link type="primary" @click="goCreateTicket">改用表单报修</el-button>
        </template>
      </el-alert>

      <div ref="messageListRef" v-loading="messagesLoading" class="message-list" @scroll="onMessageScroll">
        <el-empty v-if="!currentSessionId" description="新建或选择会话开始提问" />
        <template v-else>
          <div v-if="!messages.length && !sending" class="chat-hint">
            <p>可以这样问：</p>
            <el-button link type="primary" @click="fillPrompt('电梯困人怎么处理？')">电梯困人怎么处理？</el-button>
            <el-button link type="primary" @click="fillPrompt('A 栋 2 号梯有异响，需要报修')">A 栋 2 号梯有异响，需要报修</el-button>
            <el-button link type="primary" @click="fillPrompt('消防水泵多久巡检一次？')">消防水泵多久巡检一次？</el-button>
          </div>

          <div v-for="item in messages" :key="item.id" :class="['message', item.role.toLowerCase()]">
            <div class="bubble">
              <div v-if="item.role === 'ASSISTANT'" class="markdown" v-html="renderMarkdown(item.content)"></div>
              <div v-else class="plain">{{ item.content }}</div>
              <div v-if="item.citations?.length" class="citations">
                <el-tag
                  v-for="citation in item.citations"
                  :key="citation.chunkId"
                  size="small"
                  effect="plain"
                  class="citation-tag"
                  title="点击下载知识原文"
                  @click="downloadCitation(citation)"
                >
                  [{{ citation.rank }}] {{ citation.title || "知识片段" }}
                  <span v-if="citation.score != null"> {{ Math.round(citation.score * 100) }}%</span>
                </el-tag>
              </div>
              <div v-if="item.triage" class="triage-line">
                <el-tag size="small" type="info">{{ intentLabel(item.triage.intentCode) }}</el-tag>
                <span class="muted">置信度 {{ Math.round((item.triage.confidence || 0) * 100) }}%</span>
                <el-tag v-if="item.triage.lowConfidence" size="small" type="warning">低置信</el-tag>
                <span class="muted">{{ actionLabel(item.triage.action) }}</span>
              </div>
              <div v-if="item.ticketCard && !item.ticketCard.hidden && !hasCreateToolCall(item.toolCalls)" class="ticket-card">
                <div class="card-head">
                  <el-tag size="small" :type="item.ticketCard.state === 'CREATED' ? 'success' : 'warning'">
                    {{ item.ticketCard.state === "CREATED" ? "已建单" : "工单草稿" }}
                  </el-tag>
                  <strong>{{ item.ticketCard.draft.title || "未命名工单" }}</strong>
                </div>
                <div class="card-meta">
                  <span>类型：{{ item.ticketCard.draft.ticketTypeCode || "GENERAL" }}</span>
                  <span>优先级：{{ item.ticketCard.draft.priorityCode || "NORMAL" }}</span>
                  <span v-if="item.ticketCard.draft.locationText">位置：{{ item.ticketCard.draft.locationText }}</span>
                </div>
                <div v-if="item.ticketCard.draft.description" class="card-desc">{{ item.ticketCard.draft.description }}</div>
                <div v-if="item.ticketCard.state === 'CREATED'" class="card-done">
                  工单号 {{ item.ticketCard.ticketNo }} · {{ item.ticketCard.statusCode }}
                  <router-link to="/tickets">查看工单</router-link>
                </div>
                <div v-else class="card-actions">
                  <el-button size="small" @click="item.ticketCard.hidden = true">忽略</el-button>
                  <el-button
                    size="small"
                    type="primary"
                    :loading="confirmingTriageId === item.ticketCard.triageId"
                    @click="confirmCard(item.ticketCard)"
                  >
                    确认建单
                  </el-button>
                </div>
              </div>
              <div v-if="item.toolCalls?.length" class="tool-calls">
                <div v-for="tool in (item.toolCalls || [])" v-show="!tool.hidden" :key="tool.toolCallId" class="tool-call">
                  <template v-if="tool.name === 'ticket_create'">
                    <div v-if="tool.status === 'PENDING'" class="ticket-card">
                      <div class="card-head">
                        <el-tag size="small" type="warning">工具建单草稿</el-tag>
                        <strong>{{ toolDraft(tool).title || "未命名工单" }}</strong>
                      </div>
                      <div class="card-meta">
                        <span>类型：{{ toolDraft(tool).ticketTypeCode || "GENERAL" }}</span>
                        <span>优先级：{{ toolDraft(tool).priorityCode || "NORMAL" }}</span>
                        <span v-if="toolDraft(tool).locationText">位置：{{ toolDraft(tool).locationText }}</span>
                      </div>
                      <div v-if="toolDraft(tool).description" class="card-desc">{{ toolDraft(tool).description }}</div>
                      <div class="card-actions">
                        <el-button size="small" @click="tool.hidden = true">忽略</el-button>
                        <el-button
                          size="small"
                          type="primary"
                          :loading="confirmingToolId === tool.id"
                          @click="confirmTool(tool)"
                        >
                          确认建单
                        </el-button>
                      </div>
                    </div>
                    <div v-else-if="tool.status === 'SUCCESS'" class="tool-line">
                      <el-tag size="small" type="success">已建单</el-tag>
                      <span>{{ tool.resultSummary }}</span>
                      <router-link to="/tickets">查看工单</router-link>
                    </div>
                    <div v-else-if="tool.status === 'CANCELLED'" class="tool-line">
                      <el-tag size="small" type="info">已取消</el-tag>
                      <span class="muted">已通过其他方式建单或取消</span>
                    </div>
                    <div v-else class="tool-line tool-failed">
                      <el-tag size="small" type="danger">建单失败</el-tag>
                      <span>{{ tool.errorCode || tool.resultSummary }}</span>
                    </div>
                  </template>
                  <div v-else class="tool-line">
                    <el-tag size="small" :type="tool.status === 'SUCCESS' ? 'success' : 'danger'">
                      {{ tool.name }}
                    </el-tag>
                    <span class="muted">{{ tool.argumentsSummary }}</span>
                    <span class="tool-result">{{ tool.resultSummary || tool.errorCode }}</span>
                  </div>
                </div>
              </div>
              <el-collapse v-if="hasProcess(item)" class="process-collapse">
                <el-collapse-item :title="`处理过程（${processSteps(item).length} 步）`" name="process">
                  <el-timeline>
                    <el-timeline-item v-for="(step, idx) in processSteps(item)" :key="idx">
                      <div class="process-step-title">{{ idx + 1 }}. {{ step.title }}</div>
                      <el-collapse v-if="step.detail">
                        <el-collapse-item title="参数与返回">
                          <pre class="process-pre">{{ step.detail }}</pre>
                        </el-collapse-item>
                      </el-collapse>
                    </el-timeline-item>
                  </el-timeline>
                </el-collapse-item>
              </el-collapse>
              <div v-if="item.status === 'FAILED'" class="failed">
                <span>生成中断{{ item.errorCode ? `（${item.errorCode}）` : "" }}，可重新发送</span>
                <el-button link type="primary" @click="goCreateTicket">改用表单报修</el-button>
              </div>
            </div>
          </div>

          <div v-if="sending" class="message assistant">
            <div class="bubble">
              <div class="markdown" v-html="renderMarkdown(streamingContent || '正在思考…')"></div>
              <div v-if="streamingCitations.length" class="citations">
                <el-tag
                  v-for="citation in streamingCitations"
                  :key="citation.chunkId"
                  size="small"
                  effect="plain"
                  class="citation-tag"
                  title="点击下载知识原文"
                  @click="downloadCitation(citation)"
                >
                  [{{ citation.rank }}] {{ citation.title || "知识片段" }}
                </el-tag>
              </div>
              <div v-if="liveTriage" class="triage-line">
                <el-tag size="small" type="info">{{ intentLabel(liveTriage.intentCode) }}</el-tag>
                <span class="muted">置信度 {{ Math.round((liveTriage.confidence || 0) * 100) }}%</span>
                <el-tag v-if="liveTriage.lowConfidence" size="small" type="warning">低置信</el-tag>
                <span class="muted">{{ actionLabel(liveTriage.action) }}</span>
              </div>
              <div v-if="liveCard && !liveCard.hidden && !hasCreateToolCall(liveToolCalls)" class="ticket-card">
                <div class="card-head">
                  <el-tag size="small" :type="liveCard.state === 'CREATED' ? 'success' : 'warning'">
                    {{ liveCard.state === "CREATED" ? "已建单" : "工单草稿" }}
                  </el-tag>
                  <strong>{{ liveCard.draft.title || "未命名工单" }}</strong>
                </div>
                <div class="card-meta">
                  <span>类型：{{ liveCard.draft.ticketTypeCode || "GENERAL" }}</span>
                  <span>优先级：{{ liveCard.draft.priorityCode || "NORMAL" }}</span>
                  <span v-if="liveCard.draft.locationText">位置：{{ liveCard.draft.locationText }}</span>
                </div>
                <div v-if="liveCard.draft.description" class="card-desc">{{ liveCard.draft.description }}</div>
                <div v-if="liveCard.state === 'CREATED'" class="card-done">
                  工单号 {{ liveCard.ticketNo }} · {{ liveCard.statusCode }}
                  <router-link to="/tickets">查看工单</router-link>
                </div>
                <div v-else class="card-actions">
                  <el-button size="small" @click="liveCard.hidden = true">忽略</el-button>
                  <el-button
                    size="small"
                    type="primary"
                    :loading="confirmingTriageId === liveCard.triageId"
                    @click="confirmCard(liveCard)"
                  >
                    确认建单
                  </el-button>
                </div>
              </div>
              <div v-if="liveToolCalls.length" class="tool-calls">
                <div v-for="tool in liveToolCalls" v-show="!tool.hidden" :key="tool.toolCallId" class="tool-call">
                  <template v-if="tool.name === 'ticket_create'">
                    <div v-if="tool.status === 'PENDING'" class="ticket-card">
                      <div class="card-head">
                        <el-tag size="small" type="warning">工具建单草稿</el-tag>
                        <strong>{{ toolDraft(tool).title || "未命名工单" }}</strong>
                      </div>
                      <div class="card-meta">
                        <span>类型：{{ toolDraft(tool).ticketTypeCode || "GENERAL" }}</span>
                        <span>优先级：{{ toolDraft(tool).priorityCode || "NORMAL" }}</span>
                        <span v-if="toolDraft(tool).locationText">位置：{{ toolDraft(tool).locationText }}</span>
                      </div>
                      <div v-if="toolDraft(tool).description" class="card-desc">{{ toolDraft(tool).description }}</div>
                      <div class="card-actions">
                        <el-button size="small" @click="tool.hidden = true">忽略</el-button>
                        <el-button
                          size="small"
                          type="primary"
                          :loading="confirmingToolId === tool.id"
                          @click="confirmTool(tool)"
                        >
                          确认建单
                        </el-button>
                      </div>
                    </div>
                    <div v-else-if="tool.status === 'SUCCESS'" class="tool-line">
                      <el-tag size="small" type="success">已建单</el-tag>
                      <span>{{ tool.resultSummary }}</span>
                      <router-link to="/tickets">查看工单</router-link>
                    </div>
                    <div v-else-if="tool.status === 'CANCELLED'" class="tool-line">
                      <el-tag size="small" type="info">已取消</el-tag>
                      <span class="muted">已通过其他方式建单或取消</span>
                    </div>
                    <div v-else class="tool-line tool-failed">
                      <el-tag size="small" type="danger">建单失败</el-tag>
                      <span>{{ tool.errorCode || tool.resultSummary }}</span>
                    </div>
                  </template>
                  <div v-else class="tool-line">
                    <el-tag size="small" :type="tool.status === 'SUCCESS' ? 'success' : 'danger'">
                      {{ tool.name }}
                    </el-tag>
                    <span class="muted">{{ tool.argumentsSummary }}</span>
                    <span class="tool-result">{{ tool.resultSummary || tool.errorCode }}</span>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </template>
      </div>

      <div class="quick-cards">
        <div v-for="card in quickCards" :key="card.title" class="quick-card" @click="useQuickCard(card)">
          <div class="quick-card-title">{{ card.title }}</div>
          <div class="quick-card-desc">{{ card.desc }}</div>
        </div>
      </div>

      <div class="composer">
        <el-input
          v-model="input"
          type="textarea"
          :rows="2"
          resize="none"
          :disabled="!currentSessionId || sending"
          placeholder="描述你的问题，Enter 发送，Shift+Enter 换行"
          @keydown.enter.exact.prevent="send"
        />
        <div class="composer-actions">
          <span class="muted">回答基于知识库检索，仅供参考</span>
          <el-button type="primary" :loading="sending" :disabled="!currentSessionId || !input.trim()" @click="send">
            发送
          </el-button>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { Delete } from "@element-plus/icons-vue";
import MarkdownIt from "markdown-it";
import DOMPurify from "dompurify";
import { getAiHealth } from "@/api/health";
import { http, toErrorMessage } from "@/api/http";
import {
  archiveChatSession,
  confirmToolCall,
  confirmTriage,
  createChatSession,
  listChatMessages,
  listChatSessions,
  listTriageRecords,
  streamChatMessage,
  type ChatCitation,
  type ChatMessage,
  type ChatSession,
  type ChatToolCall,
  type TicketCard,
  type TicketDraft,
  type TriageRecord,
} from "@/api/chat";

const md = new MarkdownIt({ html: false, linkify: true, breaks: true });
const route = useRoute();
const router = useRouter();

const sessions = ref<ChatSession[]>([]);
const sessionsLoading = ref(false);
const currentSessionId = ref<string>("");
const messages = ref<ChatMessage[]>([]);
const messagesLoading = ref(false);
const input = ref("");
const sending = ref(false);
const streamingContent = ref("");
const streamingCitations = ref<ChatCitation[]>([]);
const liveTriage = ref<TriageRecord | null>(null);
const liveCard = ref<TicketCard | null>(null);
const liveToolCalls = ref<ChatToolCall[]>([]);
const confirmingTriageId = ref("");
const confirmingToolId = ref("");
const messageListRef = ref<HTMLElement | null>(null);
const aiDegraded = ref(false);
let abortController: AbortController | null = null;
let aiHealthTimer: number | undefined;
let deltaBuffer = "";
let deltaTimer: number | undefined;
let stickToBottom = true;

const currentSession = computed(() => sessions.value.find((item) => item.id === currentSessionId.value));

async function loadAiHealth(): Promise<void> {
  try {
    const health = await getAiHealth();
    aiDegraded.value = health.status !== "UP";
  } catch {
    // 健康检查失败不打扰用户；真正的对话失败会给出明确提示。
  }
}

function goCreateTicket(): void {
  void router.push({ path: "/tickets", query: { create: "1" } });
}

const quickCards = [
  { title: "查询工单", desc: "查看我的工单与进度", prompt: "帮我查一下我最近的工单" },
  { title: "创建工单", desc: "描述故障，生成报修草稿", prompt: "帮我报修：" },
  { title: "相似工单", desc: "查找历史类似故障", prompt: "以前有没有类似的故障工单？故障描述：" },
  { title: "检索知识", desc: "查询流程、规范与处置方案", prompt: "请检索知识库：" },
];

const INTENT_LABELS: Record<string, string> = {
  FAULT_REPORT: "故障报修",
  QUERY: "咨询",
  OPERATION: "操作指引",
  COMPLAINT: "投诉",
  OTHER: "其他",
};

const ACTION_LABELS: Record<string, string> = {
  ANSWER: "直接回答",
  CREATE_DRAFT: "生成工单草稿",
  CLARIFY: "需要补充信息",
  HANDOFF: "建议转人工",
};

function renderMarkdown(text: string): string {
  return DOMPurify.sanitize(md.render(text || ""));
}

function intentLabel(code: string): string {
  return INTENT_LABELS[code] || code || "其他";
}

function actionLabel(code: string): string {
  return ACTION_LABELS[code] || code || "";
}

function cardFromTriage(triage: TriageRecord): TicketCard | null {
  if (!triage.draft) return null;
  if (triage.draftStatus !== "DRAFT" && triage.draftStatus !== "CONFIRMED") return null;
  const confirmed = triage.draftStatus === "CONFIRMED";
  const draft: TicketDraft = {
    title: triage.draft.title,
    description: triage.draft.description,
    ticketTypeCode: triage.draft.ticketTypeCode,
    priorityCode: triage.draft.priorityCode,
    locationText: triage.draft.locationText,
  };
  return {
    state: confirmed ? "CREATED" : "DRAFT",
    triageId: triage.id,
    requiresConfirm: !confirmed,
    draft,
    ticketId: triage.ticketId,
    ticketNo: triage.ticketNo,
    statusCode: triage.ticketStatusCode,
  };
}

function processSteps(message: ChatMessage): { title: string; detail?: string }[] {
  const steps: { title: string; detail?: string }[] = [];
  if (message.triage) {
    steps.push({
      title: `分诊：${intentLabel(message.triage.intentCode)} · 置信度 ${Math.round((message.triage.confidence || 0) * 100)}%`,
    });
  }
  for (const tool of message.toolCalls ?? []) {
    const args = tool.arguments ? JSON.stringify(tool.arguments, null, 2) : "无";
    const result = tool.resultSummary || tool.errorCode || "无返回摘要";
    steps.push({
      title: `工具 ${tool.name} · ${tool.status}`,
      detail: `参数：\n${args}\n\n返回：\n${result}`,
    });
  }
  if (message.citations?.length) {
    steps.push({ title: `知识检索：命中 ${message.citations.length} 条` });
  }
  return steps;
}

function hasProcess(message: ChatMessage): boolean {
  return processSteps(message).length > 0;
}

function attachTriages(list: ChatMessage[], triages: TriageRecord[]): ChatMessage[] {
  const byMessage = new Map<string, TriageRecord>();
  for (const triage of triages) {
    if (triage.messageId && !byMessage.has(triage.messageId)) {
      byMessage.set(triage.messageId, triage);
    }
  }
  return list.map((message) => {
    const triage = byMessage.get(message.id);
    return triage ? { ...message, triage, ticketCard: cardFromTriage(triage) } : message;
  });
}

function useQuickCard(card: { prompt: string }): void {
  input.value = card.prompt;
}

function hasCreateToolCall(tools?: ChatToolCall[] | null): boolean {
  return Boolean(tools?.some((tool) => tool.name === "ticket_create"));
}

function toolDraft(call: ChatToolCall): TicketDraft {
  const args: Record<string, any> = call.arguments ?? {};
  return {
    title: args.title,
    description: args.description,
    ticketTypeCode: args.ticket_type_code,
    priorityCode: args.priority_code,
    locationText: args.location_text,
  };
}

async function confirmTool(call: ChatToolCall): Promise<void> {
  if (call.status !== "PENDING" || confirmingToolId.value) return;
  confirmingToolId.value = call.id;
  try {
    const ticket = await confirmToolCall(call.id);
    call.status = "SUCCESS";
    call.resultSummary = `已创建工单 ${ticket.ticketNo}`;
    call.resultTicketId = ticket.id;
    ElMessage.success(`已创建工单 ${ticket.ticketNo}`);
  } catch (error: any) {
    ElMessage.error(toErrorMessage(error, "确认建单失败"));
  } finally {
    confirmingToolId.value = "";
  }
}

async function confirmCard(card: TicketCard): Promise<void> {
  if (card.state !== "DRAFT" || confirmingTriageId.value) return;
  confirmingTriageId.value = card.triageId;
  try {
    const ticket = await confirmTriage(card.triageId);
    card.state = "CREATED";
    card.requiresConfirm = false;
    card.ticketId = ticket.id;
    card.ticketNo = ticket.ticketNo;
    card.statusCode = ticket.statusCode;
    ElMessage.success(`已创建工单 ${ticket.ticketNo}`);
  } catch (error: any) {
    ElMessage.error(toErrorMessage(error, "确认建单失败"));
  } finally {
    confirmingTriageId.value = "";
  }
}

function formatTime(value?: string | null): string {
  return value ? new Date(value).toLocaleString() : "";
}

function onMessageScroll(): void {
  const element = messageListRef.value;
  if (!element) return;
  // 只有用户接近底部时才自动跟随，避免向上翻阅时被强制拉回。
  stickToBottom = element.scrollHeight - element.scrollTop - element.clientHeight < 80;
}

async function scrollToBottom(force = false): Promise<void> {
  if (!force && !stickToBottom) return;
  await nextTick();
  const element = messageListRef.value;
  if (element) element.scrollTop = element.scrollHeight;
}

/** 30–50ms 合批，避免每个 token 都触发 Markdown 解析与 nextTick。 */
function queueDelta(delta: string): void {
  deltaBuffer += delta;
  if (deltaTimer !== undefined) return;
  deltaTimer = window.setTimeout(flushDelta, 40);
}

function flushDelta(): void {
  if (deltaTimer !== undefined) {
    window.clearTimeout(deltaTimer);
    deltaTimer = undefined;
  }
  if (!deltaBuffer) return;
  streamingContent.value += deltaBuffer;
  deltaBuffer = "";
  void scrollToBottom();
}

async function downloadCitation(citation: ChatCitation): Promise<void> {
  try {
    const body = (await http.get(`/knowledge/docs/${citation.docId}/download`)) as any;
    if (body.code !== 0) throw new Error(body.message);
    window.open(body.data.url, "_blank", "noopener");
  } catch (error: any) {
    ElMessage.error(toErrorMessage(error, "知识下载失败"));
  }
}

async function loadSessions(): Promise<void> {
  sessionsLoading.value = true;
  try {
    sessions.value = await listChatSessions();
    if (!currentSessionId.value && sessions.value.length) {
      await selectSession(sessions.value[0].id);
    }
  } catch (error: any) {
    ElMessage.error(toErrorMessage(error, "加载会话失败"));
  } finally {
    sessionsLoading.value = false;
  }
}

async function selectSession(sessionId: string): Promise<void> {
  if (sending.value) return;
  currentSessionId.value = sessionId;
  messagesLoading.value = true;
  try {
    const [list, triages] = await Promise.all([
      listChatMessages(sessionId),
      listTriageRecords(sessionId).catch(() => [] as TriageRecord[]),
    ]);
    messages.value = attachTriages(list, triages);
    stickToBottom = true;
    await scrollToBottom(true);
  } catch (error: any) {
    ElMessage.error(toErrorMessage(error, "加载消息失败"));
  } finally {
    messagesLoading.value = false;
  }
}

async function startNewSession(): Promise<void> {
  if (sending.value) return;
  try {
    const session = await createChatSession();
    sessions.value = [session, ...sessions.value];
    currentSessionId.value = session.id;
    messages.value = [];
    input.value = "";
    stickToBottom = true;
    await scrollToBottom(true);
  } catch (error: any) {
    ElMessage.error(toErrorMessage(error, "新建会话失败"));
  }
}

async function removeSession(sessionId: string): Promise<void> {
  try {
    await ElMessageBox.confirm("归档后该会话将从列表中移除，是否继续？", "归档会话", {
      type: "warning",
      confirmButtonText: "归档",
      cancelButtonText: "取消",
    });
  } catch {
    return;
  }
  try {
    await archiveChatSession(sessionId);
    sessions.value = sessions.value.filter((item) => item.id !== sessionId);
    if (currentSessionId.value === sessionId) {
      currentSessionId.value = "";
      messages.value = [];
      if (sessions.value.length) await selectSession(sessions.value[0].id);
    }
  } catch (error: any) {
    ElMessage.error(toErrorMessage(error, "归档失败"));
  }
}

function fillPrompt(text: string): void {
  input.value = text;
}

async function send(): Promise<void> {
  const content = input.value.trim();
  if (!content || !currentSessionId.value || sending.value) return;
  const sessionId = currentSessionId.value;
  const clientMessageId = `c-${crypto.randomUUID()}`;
  input.value = "";
  messages.value.push({
    id: `local-${clientMessageId}`,
    role: "USER",
    content,
    status: "COMPLETED",
    citations: [],
  });
  sending.value = true;
  streamingContent.value = "";
  streamingCitations.value = [];
  liveTriage.value = null;
  liveCard.value = null;
  liveToolCalls.value = [];
  deltaBuffer = "";
  if (deltaTimer !== undefined) {
    window.clearTimeout(deltaTimer);
    deltaTimer = undefined;
  }
  stickToBottom = true;
  abortController = new AbortController();
  await scrollToBottom(true);

  let assistantId = "";
  let traceId = "";
  const failure: { value: { code: number; message: string } | null } = { value: null };

  try {
    await streamChatMessage(
      sessionId,
      { clientMessageId, content, contentType: "TEXT" },
      {
        onMeta(data) {
          assistantId = data.assistantMessageId ?? "";
          traceId = data.traceId ?? "";
        },
        onDelta(delta) {
          queueDelta(delta);
        },
        onCitation(citation) {
          streamingCitations.value.push(citation);
        },
        onTriage(data) {
          liveTriage.value = {
            id: data.triageId,
            messageId: assistantId || null,
            intentCode: data.intent,
            entities: {},
            confidence: data.confidence,
            action: data.action,
            lowConfidence: data.lowConfidence,
            draft: null,
            draftStatus: null,
          };
        },
        onTicketCard(card) {
          liveCard.value = card;
          if (liveTriage.value) {
            liveTriage.value = { ...liveTriage.value, draft: card.draft, draftStatus: "DRAFT" };
          }
        },
        onToolCall(tool) {
          const existing = liveToolCalls.value.find((item) => item.toolCallId === tool.toolCallId);
          if (existing) {
            Object.assign(existing, tool);
          } else {
            liveToolCalls.value.push(tool);
          }
        },
        onDone() {
          // 收尾统一在下方处理
        },
        onErrorEvent(data) {
          failure.value = {
            code: data.code,
            message: data.message && traceId ? `${data.message}（追踪号：${traceId}）` : data.message,
          };
        },
      },
      abortController.signal,
    );
  } catch (error: any) {
    if (!failure.value) {
      failure.value = { code: 0, message: toErrorMessage(error, "对话流中断，请稍后重试") };
    }
  } finally {
    flushDelta();
    const finalContent = streamingContent.value;
    const failureValue = failure.value;
    const triageSnapshot = liveTriage.value;
    const cardSnapshot = liveCard.value;
    const toolCallsSnapshot = liveToolCalls.value.slice();
    if (failureValue) {
      messages.value.push({
        id: assistantId || `local-${clientMessageId}-a`,
        role: "ASSISTANT",
        content: finalContent,
        status: "FAILED",
        errorCode: failureValue.message,
        citations: streamingCitations.value.slice(),
        triage: triageSnapshot,
        ticketCard: cardSnapshot,
        toolCalls: toolCallsSnapshot,
      });
      ElMessage.error(failureValue.message || "生成失败");
    } else {
      messages.value.push({
        id: assistantId || `local-${clientMessageId}-a`,
        role: "ASSISTANT",
        content: finalContent,
        status: "COMPLETED",
        citations: streamingCitations.value.slice(),
        triage: triageSnapshot,
        ticketCard: cardSnapshot,
        toolCalls: toolCallsSnapshot,
      });
    }
    streamingContent.value = "";
    streamingCitations.value = [];
    liveTriage.value = null;
    liveCard.value = null;
    liveToolCalls.value = [];
    sending.value = false;
    abortController = null;
    await scrollToBottom();
  }
}

onMounted(() => {
  const prompt = route.query.prompt;
  if (typeof prompt === "string" && prompt.trim()) {
    input.value = prompt.trim();
  }
  void loadSessions();
  void loadAiHealth();
  aiHealthTimer = window.setInterval(() => void loadAiHealth(), 30_000);
});
onBeforeUnmount(() => {
  abortController?.abort();
  if (aiHealthTimer !== undefined) {
    window.clearInterval(aiHealthTimer);
  }
  if (deltaTimer !== undefined) {
    window.clearTimeout(deltaTimer);
  }
});
</script>

<style scoped>
.chat-page {
  display: flex;
  height: calc(100vh - 96px);
  gap: 16px;
}
.session-panel {
  width: 260px;
  flex: none;
  display: flex;
  flex-direction: column;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  background: #fff;
  overflow: hidden;
}
.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 14px;
  border-bottom: 1px solid #ebeef5;
  font-weight: 600;
}
.session-list {
  flex: 1;
  overflow-y: auto;
  padding: 6px;
}
.session-item {
  position: relative;
  padding: 10px 12px;
  border-radius: 6px;
  cursor: pointer;
}
.session-item:hover {
  background: #f5f7fa;
}
.session-item.active {
  background: #ecf5ff;
}
.session-title {
  font-size: 14px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  padding-right: 18px;
}
.session-meta {
  margin-top: 4px;
  color: #909399;
  font-size: 12px;
}
.session-del {
  position: absolute;
  top: 10px;
  right: 8px;
  color: #c0c4cc;
  display: none;
}
.session-item:hover .session-del {
  display: block;
}
.chat-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  background: #fff;
  overflow: hidden;
}
.chat-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid #ebeef5;
}
.chat-head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.ai-degraded {
  margin: 8px 16px 0;
}
.session-no {
  margin-left: 10px;
  font-size: 12px;
}
.message-list {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  background: #fafafa;
}
.chat-hint {
  color: #606266;
  font-size: 14px;
}
.chat-hint p {
  margin: 0 0 10px;
  color: #909399;
}
.message {
  display: flex;
  margin-bottom: 14px;
}
.message.user {
  justify-content: flex-end;
}
.bubble {
  max-width: 76%;
  padding: 10px 14px;
  border-radius: 8px;
  background: #fff;
  border: 1px solid #ebeef5;
  line-height: 1.65;
  font-size: 14px;
}
.message.user .bubble {
  background: #ecf5ff;
  border-color: #d9ecff;
}
.plain {
  white-space: pre-wrap;
  word-break: break-word;
}
.markdown :deep(p) {
  margin: 0 0 8px;
}
.markdown :deep(p:last-child) {
  margin-bottom: 0;
}
.markdown :deep(pre) {
  background: #f5f7fa;
  padding: 10px;
  border-radius: 6px;
  overflow-x: auto;
}
.markdown :deep(code) {
  background: #f5f7fa;
  padding: 1px 4px;
  border-radius: 3px;
  font-size: 13px;
}
.markdown :deep(ol),
.markdown :deep(ul) {
  padding-left: 20px;
  margin: 6px 0;
}
.citations {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed #ebeef5;
}
.citation-tag {
  cursor: pointer;
}
.citation-tag:hover {
  color: #409eff;
  border-color: #409eff;
}
.failed {
  margin-top: 8px;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  color: #f56c6c;
  font-size: 12px;
}
.tool-calls {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.tool-call .ticket-card {
  margin-top: 0;
}
.tool-line {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 12px;
}
.tool-result {
  color: #606266;
}
.tool-failed {
  color: #f56c6c;
}
.triage-line {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed #ebeef5;
}
.ticket-card {
  margin-top: 10px;
  padding: 10px 12px;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  background: #fcfcfd;
}
.card-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.card-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  color: #606266;
  font-size: 12px;
}
.card-desc {
  margin-top: 6px;
  color: #606266;
  font-size: 13px;
  white-space: pre-wrap;
}
.card-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 10px;
}
.card-done {
  margin-top: 8px;
  color: #67c23a;
  font-size: 13px;
  display: flex;
  align-items: center;
  gap: 10px;
}
.quick-cards {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  padding: 10px 16px 0;
  border-top: 1px solid #ebeef5;
}
.quick-card {
  padding: 8px 10px;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  background: #fafafa;
  cursor: pointer;
  transition: border-color 0.2s, background 0.2s;
}
.quick-card:hover {
  border-color: #409eff;
  background: #ecf5ff;
}
.quick-card-title {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
}
.quick-card-desc {
  margin-top: 3px;
  font-size: 11px;
  color: #909399;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.composer {
  border-top: 1px solid #ebeef5;
  padding: 12px 16px;
}
.process-collapse {
  margin-top: 10px;
  border-top: 1px dashed #ebeef5;
  border-bottom: none;
}
.process-step-title {
  font-size: 13px;
  color: #303133;
}
.process-pre {
  margin: 0;
  padding: 8px;
  background: #f5f7fa;
  border-radius: 6px;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}
.composer-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 8px;
}
.muted {
  color: #909399;
  font-size: 12px;
}
</style>
