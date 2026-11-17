import { fetchEventSource } from "@microsoft/fetch-event-source";
import { getAccessToken, http } from "@/api/http";

export interface ChatSession {
  id: string;
  sessionNo: string;
  title: string | null;
  status: string;
  messageCount: number;
  lastMessageAt?: string | null;
  createdAt?: string | null;
}

export interface ChatCitation {
  docId: string;
  chunkId: string;
  title: string;
  score: number | null;
  rank: number | null;
}

export interface TicketDraft {
  title?: string;
  description?: string;
  ticketTypeCode?: string;
  priorityCode?: string;
  locationText?: string;
}

export interface TicketCard {
  state: "DRAFT" | "CREATED";
  triageId: string;
  requiresConfirm: boolean;
  draft: TicketDraft;
  ticketId?: string | null;
  ticketNo?: string | null;
  statusCode?: string | null;
  hidden?: boolean;
}

export interface TriageRecord {
  id: string;
  messageId: string | null;
  intentCode: string;
  entities: Record<string, unknown>;
  confidence: number;
  action: string;
  lowConfidence: boolean;
  draft: TicketDraft | null;
  draftStatus: string | null;
  createdAt?: string | null;
  confirmedAt?: string | null;
  ticketId?: string | null;
  ticketNo?: string | null;
  ticketStatusCode?: string | null;
}

export interface ChatToolCall {
  id: string;
  toolCallId: string;
  name: string;
  status: string;
  requiresConfirm: boolean;
  arguments?: Record<string, any> | null;
  argumentsSummary?: string | null;
  resultSummary?: string | null;
  resultTicketId?: string | null;
  errorCode?: string | null;
  hidden?: boolean;
}

export interface ChatMessage {
  id: string;
  role: "USER" | "ASSISTANT" | "SYSTEM" | "TOOL" | string;
  content: string;
  contentType?: string;
  status?: string;
  tokenIn?: number;
  tokenOut?: number;
  latencyMs?: number;
  errorCode?: string | null;
  createdAt?: string | null;
  citations?: ChatCitation[];
  triage?: TriageRecord | null;
  ticketCard?: TicketCard | null;
  toolCalls?: ChatToolCall[];
}

export interface ChatStreamHandlers {
  onMeta?: (data: any) => void;
  onDelta?: (content: string) => void;
  onCitation?: (citation: ChatCitation) => void;
  onTriage?: (data: any) => void;
  onTicketCard?: (card: TicketCard) => void;
  onToolCall?: (tool: ChatToolCall) => void;
  onDone?: (data: any) => void;
  onErrorEvent?: (data: any) => void;
}

export async function listChatSessions(): Promise<ChatSession[]> {
  const body = (await http.get("/chat/sessions", { params: { page: 1, pageSize: 50 } })) as any;
  if (body.code !== 0) throw new Error(body.message);
  return body.data.items ?? [];
}

export async function createChatSession(title?: string): Promise<ChatSession> {
  const body = (await http.post("/chat/sessions", { title: title ?? null })) as any;
  if (body.code !== 0) throw new Error(body.message);
  return body.data;
}

export async function listChatMessages(sessionId: string, limit = 50): Promise<ChatMessage[]> {
  const body = (await http.get(`/chat/sessions/${sessionId}/messages`, {
    params: { limit },
  })) as any;
  if (body.code !== 0) throw new Error(body.message);
  return body.data ?? [];
}

export async function closeChatSession(sessionId: string): Promise<void> {
  const body = (await http.post(`/chat/sessions/${sessionId}/close`)) as any;
  if (body.code !== 0) throw new Error(body.message);
}

export async function archiveChatSession(sessionId: string): Promise<void> {
  const body = (await http.delete(`/chat/sessions/${sessionId}`)) as any;
  if (body.code !== 0) throw new Error(body.message);
}

export async function listTriageRecords(sessionId: string): Promise<TriageRecord[]> {
  const body = (await http.get(`/chat/sessions/${sessionId}/triage-records`)) as any;
  if (body.code !== 0) throw new Error(body.message);
  return body.data ?? [];
}

export async function confirmToolCall(
  id: string,
): Promise<{ id: string; ticketNo: string; statusCode: string }> {
  const body = (await http.post(`/chat/tool-calls/${id}/confirm`, {})) as any;
  if (body.code !== 0) throw new Error(body.message);
  return body.data.ticket;
}

export async function confirmTriage(
  triageId: string,
  overrides?: TicketDraft,
): Promise<{ id: string; ticketNo: string; statusCode: string }> {
  const body = (await http.post(`/chat/triage-records/${triageId}/confirm`, overrides ?? {})) as any;
  if (body.code !== 0) throw new Error(body.message);
  return body.data.ticket;
}

/**
 * POST SSE 对话流。浏览器 EventSource 不支持 POST，使用 fetch-event-source。
 * 断线不自动重连；重试由用户以相同 clientMessageId 发起。
 */
export async function streamChatMessage(
  sessionId: string,
  payload: { clientMessageId: string; content: string; contentType?: string },
  handlers: ChatStreamHandlers,
  signal: AbortSignal,
): Promise<void> {
  const token = getAccessToken();
  const requestId = crypto.randomUUID().replace(/-/g, "");
  await fetchEventSource(`/api/v1/chat/sessions/${sessionId}/messages`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Accept: "text/event-stream",
      "X-Request-Id": requestId,
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify({
      clientMessageId: payload.clientMessageId,
      content: payload.content,
      contentType: payload.contentType ?? "TEXT",
    }),
    signal,
    openWhenHidden: true,
    async onopen(response) {
      if (!response.ok) {
        let message = `请求失败 (${response.status})`;
        try {
          const body = await response.json();
          message = body?.message || message;
        } catch {
          // 非 JSON 错误体
        }
        throw new Error(message);
      }
    },
    onmessage(message) {
      if (!message.event || !message.data) return;
      let data: any;
      try {
        data = JSON.parse(message.data);
      } catch {
        return;
      }
      switch (message.event) {
        case "meta":
          handlers.onMeta?.(data);
          break;
        case "delta":
          handlers.onDelta?.(data.content ?? "");
          break;
        case "citation":
          handlers.onCitation?.(data as ChatCitation);
          break;
        case "triage":
          handlers.onTriage?.(data);
          break;
        case "ticket_card":
          handlers.onTicketCard?.(data as TicketCard);
          break;
        case "tool_call":
          handlers.onToolCall?.(data as ChatToolCall);
          break;
        case "done":
          handlers.onDone?.(data);
          break;
        case "error":
          handlers.onErrorEvent?.(data);
          break;
        default:
          break;
      }
    },
    onerror(error) {
      throw error instanceof Error ? error : new Error("对话流异常");
    },
  });
}
