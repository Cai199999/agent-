# 08-Agent 调用接入指南

> 项目：IT 运维工单智能 Agent（agent-gongdan）
> 日期：2026-11-18
> 适用对象：未来大项目 / Orchestrator / 其他 Agent
> 当前版本：Manifest / Agent Card `0.8.0`（主 Agent 接入 P0：能力元数据与 schema、health/ready、确认同意/拒绝、clientTaskId 恢复、事件 seq/afterSeq/Last-Event-ID）
>
> **面向主 Agent / Orchestrator 的可复制接入说明**：`docs/usage/PropertyMaintenanceAgent-主Agent接入使用说明.md`
> 该文件包含部署、鉴权、任务、确认、事件、附件、能力、错误码、指标、限制与验收清单，可直接放到主 Agent 项目的 `docs/integrations/property-maintenance/` 下。

---

## 1. 接入定位

当前平台对外暴露为：

```text
Property Maintenance Agent
  = 物业报修 / 工单 / 派单 / 现场 / 回访 / 知识 / 导出
```

调用方不要直接访问：

- MySQL / MinIO / Milvus；
- 当前 ChatController / SSE；
- 当前内部工具。

统一通过 `/agent/v1/**` 调用。

---

## 2. 鉴权

### Header

```http
X-Internal-Token: <内部令牌>
X-Actor-Id: 10001
X-Actor-Roles: ADMIN
Content-Type: application/json
```

说明：

- `X-Internal-Token` 使用项目 `.env` 的 `INTERNAL_TOKEN_P2J_CURRENT`；
- `X-Actor-Id` 必须是真实用户 ID；
- `X-Actor-Roles` 用逗号分隔；
- 当前平台仍会做票级权限和数据范围校验，不能因为“内部调用”越权。

### 委托令牌（P4）

`X-Internal-Token` 只代表“内部调用方”。如果编排器代表最终用户调用，建议签发短时委托令牌：

```http
POST /agent/v1/delegation/token
Content-Type: application/json

{
  "actorId": 10003,
  "roles": ["REPORTER"],
  "scopes": ["ticket.query", "ticket.create"],
  "ttlSeconds": 600
}
```

后续请求携带：

```http
X-Internal-Token: <内部令牌>
X-Delegation-Token: <委托令牌>
```

存在委托令牌时：

- Actor / 角色 / scope 以令牌声明为准，`X-Actor-Id` / `X-Actor-Roles` 被忽略；
- `scopes` 支持 `*` 或能力名 / `ticket.attachment.upload`；
- 令牌默认 10 分钟，最长 1 小时；
- 已有委托令牌不能再次签发委托令牌；
- 非委托调用仍兼容原有 `X-Actor-*` 头。

---

## 3. Agent Manifest

```http
GET /agent/v1/manifest
```

响应：

```json
{
  "agentId": "property-maintenance",
  "name": "物业报修与工单 Agent",
  "version": "0.8.0",
  "protocol": "agent-task-v1",
  "taskEndpoint": "/agent/v1/tasks",
  "eventStreamEndpoint": "/agent/v1/tasks/{taskId}/events/stream",
  "executionModes": ["SYNC", "ASYNC"],
  "auth": {
    "type": "internal-token",
    "header": "X-Internal-Token",
    "delegationHeader": "X-Delegation-Token"
  },
  "features": {
    "async": true,
    "confirmation": true,
    "confirmationDecision": true,
    "confirmationExpirySweep": true,
    "clientTaskLookup": true,
    "eventReplay": true,
    "lastEventId": true,
    "progress": true,
    "attachments": true
  },
  "endpoints": {
    "manifest": "/agent/v1/manifest",
    "tasks": "/agent/v1/tasks",
    "task": "/agent/v1/tasks/{taskId}",
    "taskByClientTaskId": "/agent/v1/tasks?clientTaskId={clientTaskId}",
    "confirm": "/agent/v1/tasks/{taskId}/confirm",
    "cancel": "/agent/v1/tasks/{taskId}/cancel",
    "events": "/agent/v1/tasks/{taskId}/events",
    "stream": "/agent/v1/tasks/{taskId}/events/stream",
    "attachments": "/agent/v1/tickets/{ticketId}/attachments",
    "delegation": "/agent/v1/delegation/token",
    "metrics": "/agent/v1/metrics/summary",
    "health": "/agent/v1/health",
    "ready": "/agent/v1/ready"
  },
  "healthEndpoint": "/agent/v1/health",
  "readyEndpoint": "/agent/v1/ready",
  "delegationEndpoint": "/agent/v1/delegation/token",
  "envelope": {
    "codeField": "code",
    "successCode": 0,
    "messageField": "message",
    "dataField": "data",
    "traceIdField": "traceId",
    "taskStatuses": ["PENDING", "RUNNING", "WAITING_CONFIRM", "SUCCEEDED",
                     "FAILED", "CANCELLED", "REJECTED"],
    "errorCodes": { "10001": "鉴权失败", "20001": "任务不存在", "60001": "参数校验失败" }
  },
  "contextSchema": {
    "parentTaskId": "上游任务 ID（可选）",
    "correlationId": "调用链关联 ID（可选）",
    "deadlineAt": "期望完成时间 ISO 8601（可选；执行前检查，已过期不执行）",
    "budget": "调用预算对象（可选；仅记录，不强制中断）",
    "contextRefs": "上下文引用对象；只传引用，不传完整聊天历史"
  },
  "limits": { "maxContextRefs": 50, "maxRefIdLength": 64,
              "maxInputBytes": 65536, "maxCancelReasonLength": 200 },
  "enforcement": {
    "deadlineAt": { "enforced": true, "mode": "checked-before-execution" },
    "budget": { "enforced": false, "mode": "recorded-only" },
    "runtimeTimeout": { "enforced": false }
  },
  "asyncDurability": {
    "mode": "db-recovery",
    "pendingRequeuedOnRestart": true,
    "runningReplayed": false,
    "multiInstanceLease": false,
    "note": "PENDING 启动重投；RUNNING 超时置 FAILED，写操作不自动重放"
  },
  "observability": {
    "metricsEndpoint": "/agent/v1/metrics/summary",
    "actuatorMetrics": "/actuator/metrics"
  },
  "downstream": { "enabled": false, "protocol": "agent-task-v1" },
  "capabilities": [
    {
      "capabilityId": "property.ticket.query",
      "name": "ticket.query",
      "description": "查询当前 Actor 可见工单",
      "readOnly": true,
      "requiresConfirmation": false,
      "requiresConfirm": false,
      "executionModes": ["SYNC", "ASYNC"],
      "scopes": ["ticket.query"],
      "permission": "ticket:read",
      "mode": "READ_ONLY",
      "inputSchema": {
        "type": "object",
        "properties": {
          "keyword": { "type": "string" },
          "limit": { "type": "integer", "minimum": 1, "maximum": 20 }
        },
        "additionalProperties": false
      },
      "outputSchema": {
        "type": "object",
        "properties": { "result": { "type": "array", "items": { "type": "object" } } },
        "required": ["result"]
      }
    }
  ]
}
```

调用方应缓存 Manifest，并按 `version` 处理契约变化。

能力元数据说明（0.8.0 新增）：

- `capabilityId`：稳定机器标识，格式 `property.<name>`；主 Agent 应优先用它与自身工具注册表映射；
- `readOnly` / `requiresConfirmation` / `executionModes` / `scopes`：用于自动决策是否需要人工确认、是否可异步；
- `inputSchema` / `outputSchema`：JSON Schema；`outputSchema` 描述 `result` 载荷，Task 响应外层信封见 `envelope`；
- 旧字段 `name` / `permission` / `requiresConfirm` / `mode` 保留，向后兼容。

---

## 4. 提交任务

```http
POST /agent/v1/tasks
```

请求：

```json
{
  "taskType": "ticket.query",
  "input": {
    "status": "PENDING",
    "keyword": "电梯",
    "limit": 5
  },
  "contextRefs": { "ticketId": "1001", "kind": "TICKET" },
  "clientTaskId": "orchestrator-20261118-001",
  "executionMode": "SYNC",
  "parentTaskId": "upstream-task-001",
  "correlationId": "flow-20261119-001",
  "deadlineAt": "2026-11-19T12:00:00+08:00",
  "budget": { "maxTokens": 1000, "maxLatencyMs": 10000 }
}
```

上下文参数均为可选、向后兼容：

| 字段 | 说明 | 当前行为 |
|---|---|---|
| `parentTaskId` | 上游任务 ID，形成跨 Agent 调用链 | 持久化并回显，用于审计/关联 |
| `correlationId` | 调用链关联 ID | 持久化并回显 |
| `contextRefs` | 上下文引用对象，最多 50 项 | 持久化；只允许传引用 |
| `budget` | token/耗时预算对象 | 持久化，当前不强制中断 |
| `deadlineAt` | ISO 8601 期望完成时间 | 提交时拒绝已过期；执行前再次检查，过期任务 `FAILED` + `20005`，不写业务数据；不中断执行中任务 |

> 禁止通过 `contextRefs` 传完整聊天历史、其他租户数据或原始内部令牌。下游 Agent 仍必须自行做权限校验。

响应：

```json
{
  "code": 0,
  "data": {
    "taskId": "4ab2050f-...",
    "agentId": "property-maintenance",
    "capability": "ticket.query",
    "capabilityId": "property.ticket.query",
    "status": "SUCCEEDED",
    "result": [],
    "errorCode": null,
    "errorMessage": null,
    "traceId": "...",
    "latencyMs": 12,
    "inputTokens": null,
    "outputTokens": null,
    "costMicros": null,
    "usage": {
      "inputTokens": null,
      "outputTokens": null,
      "costMicros": null,
      "source": "not-measured"
    },
    "executionMode": "SYNC",
    "delegationId": null,
    "createdAt": "2026-11-18T08:00:00Z",
    "parentTaskId": "upstream-task-001",
    "correlationId": "flow-20261119-001",
    "deadlineAt": "2026-11-19T04:00:00Z",
    "contextRefs": { "ticketId": "1001", "kind": "TICKET" },
    "budget": { "maxTokens": 1000, "maxLatencyMs": 10000 },
    "clientTaskId": "orchestrator-20261118-001",
    "progress": 100,
    "currentStep": "DONE",
    "cancelReason": null
  }
}
```

`clientTaskId` 用于幂等：同一 Actor 重复提交相同 `clientTaskId` 返回相同任务。

`progress` / `currentStep` 当前由任务状态派生（0/50/100），不代表能力内部细粒度进度；`usage.source=not-measured` 表示当前任务未产生真实 LLM 计量。

---

## 5. 查询任务与异步模式

```http
GET /agent/v1/tasks/{taskId}
GET /agent/v1/tasks?clientTaskId=orchestrator-20261120-001
```

只能查询同一 Actor（或委托令牌声明的 Actor）创建的任务。

`clientTaskId` 查询用于主 Agent 崩溃恢复：命中返回与按 `taskId` 查询相同的视图，未命中返回 `404 / 20001`。

`executionMode`：

| 值 | 行为 |
|---|---|
| `SYNC`（默认） | 请求内执行，直接返回 `SUCCEEDED` / `FAILED` |
| `ASYNC` | 只读任务返回 `PENDING`；写任务先 `WAITING_CONFIRM`，确认后返回 `PENDING`，由 Worker 执行 |

异步状态机：

```text
PENDING → RUNNING → SUCCEEDED / FAILED
PENDING / RUNNING / WAITING_CONFIRM → CANCELLED
```

调用方应轮询任务或订阅 SSE，直到终态。

---

## 6. 能力清单

| capability | input | 说明 |
|---|---|---|
| `ticket.query` | `status`、`keyword`、`limit`、`priorityCode`、`assigneeId`、`startTime`、`endTime`、`openOnly` | 查询当前 Actor 可见工单 |
| `ticket.detail` | `ticketId` 或 `ticketNo` | 工单详情 |
| `ticket.similar` | `query`、`topK` | 相似历史工单 |
| `knowledge.search` | `query`、`topK`、`categoryCode` | 知识库检索 |
| `ticket.create` | `description`、`ticketTypeCode`、`locationType`、`locationText`、`buildingId`、`roomId` 等 | 建单，需确认 |
| `ticket.export` | `startTime`、`endTime`、`status` 等 | 导出，需确认 |
| `ticket.assign` | `ticketId`、`assigneeId` | 派单，需确认 |
| `ticket.accept` | `ticketId` | 接单，需确认 |
| `ticket.resolve` | `ticketId`、`serviceItemText`、`onsiteSituation` | 提交解决，需确认；需已有 ONSITE 照片 |
| `ticket.verify` | `ticketId`、`approved` | 验证，需确认 |
| `ticket.close` | `ticketId`、`closeReasonCode` | 关闭，需确认 |
| `ticket.cancel` | `ticketId`、`cancelReason` | 取消，需确认 |
| `ticket.hold` | `ticketId`、`reason` | 挂起，需确认 |
| `ticket.resume` | `ticketId`、`reason` | 恢复，需确认 |
| `ticket.finish` | `ticketId`、`reason` | 结束，需确认 |
| `ticket.comment.add` | `ticketId`、`content`、`visibility` | 评论，需确认 |
| `ticket.material.add` | `ticketId`、`materialName`、`quantity`、`unit`、`amount` | 材料，需确认 |
| `ticket.visit.add` | `ticketId`、`visitMethod`、`visitResult`、`status` | 回访，需确认 |

只读能力立即返回 `SUCCEEDED` 或 `FAILED`；
写能力返回 `WAITING_CONFIRM`，需确认后执行。

---

## 7. 错误处理

| 场景 | 表现 |
|---|---|
| 未知 capability | `FAILED`，`errorCode=60001` |
| 任务不存在 | `404` |
| 非本人任务 | `403` |
| 内部令牌无效 | `401` |
| 已过截止时间 | `FAILED` + `20005` |
| 业务校验失败 | `FAILED` + 对应错误码 |

调用方应记录 `taskId`、`traceId`、`errorCode`。

---

## 8. 写能力与人工确认（P1 已实现）

```text
POST /agent/v1/tasks
  → status=WAITING_CONFIRM
  → 返回 confirmationToken + 摘要
  → 用户人工确认（APPROVE）或拒绝（REJECT）
  → POST /agent/v1/tasks/{id}/confirm
  → APPROVE: SUCCEEDED / FAILED；REJECT: REJECTED
```

确认或拒绝：

```http
POST /agent/v1/tasks/{taskId}/confirm
Content-Type: application/json

{"confirmationToken":"...","decision":"APPROVE"}
```

`decision` 可选，缺省 `APPROVE`；`REJECT` 进入 `REJECTED` 终态，不执行任何写操作。

规则：

- 未确认绝不写库/生成文件；
- 确认令牌 10 分钟、一次性；
- 只能由同一 Actor 确认；
- 确认后仍走现有工单/导出服务；
- 任务已落 `agent_task` 表，重启不丢失；
- 过期未确认的任务由 sweeper 置为 `FAILED`（`10002`），避免长期停在 `WAITING_CONFIRM`。

当前已支持：

- 建单、导出；
- 派单、接单、解决、验证、关闭、取消、挂起、恢复、结束；
- 评论、材料、回访。

取消：

```http
POST /agent/v1/tasks/{taskId}/cancel
Content-Type: application/json

{"reason":"主 Agent 超时"}
```

仅 `PENDING/RUNNING/WAITING_CONFIRM` 可取消；`reason` 可选、最长 200 字符，响应中通过 `cancelReason` 回显。

---

## 9. 任务事件与 SSE（P3/P4）

```http
GET /agent/v1/tasks/{taskId}/events
GET /agent/v1/tasks/{taskId}/events?afterSeq=3
```

返回该任务的生命周期事件（按 `seq` 升序），例如：

```json
{
  "code": 0,
  "data": [
    {"id": "1900...", "seq": 1, "taskId": "...", "type": "TASK_QUEUED",
     "status": "PENDING", "message": null, "createdAt": "..."},
    {"id": "1900...", "seq": 2, "taskId": "...", "type": "TASK_STARTED",
     "status": "RUNNING", "message": null, "createdAt": "..."},
    {"id": "1900...", "seq": 3, "taskId": "...", "type": "TASK_SUCCEEDED",
     "status": "SUCCEEDED", "message": null, "createdAt": "..."}
  ]
}
```

`seq` 为任务内单调序号（从 1 开始），作为断线重连游标；`afterSeq` 只返回 `seq > N` 的事件。

实时订阅：

```http
GET /agent/v1/tasks/{taskId}/events/stream
Accept: text/event-stream
Last-Event-ID: 3
```

事件格式：

```text
id:3
event:task-event
data:{"id":"...","seq":3,"taskId":"...","type":"TASK_STARTED",
      "status":"RUNNING","message":null,"createdAt":"..."}

event:done
data:{"taskId":"...","status":"SUCCEEDED"}
```

说明：

- 先回放历史事件，再增量推送；终态发送 `done` 并关闭；
- SSE `id:` 的值为 `seq`；断线重连使用 `Last-Event-ID` 或 `?afterSeq=`；
- 游标已越过终态事件时立即发送 `done`，不会空等；
- 默认 1s 轮询事件表、15s 心跳、10 分钟超时，均可配置；
- 仅同 Actor 可订阅；
- 当前为数据库轮询实现，多实例可共享，但尚未接入推送网关。

### 9.1 可观测性聚合（P6）

```http
GET /agent/v1/metrics/summary?from=2026-11-12T00:00:00%2B08:00&to=2026-11-19T23:59:59%2B08:00
X-Internal-Token: ...
X-Actor-Id: 10001
X-Actor-Roles: ADMIN
```

返回：

- `agentTasks`：总数、活跃数、终态数、成功率、状态/能力分布、P95 耗时、token 与成本合计；
- `chat`：助手消息数、失败数、token 合计、平均/P95 耗时；
- `toolCalls`：工具调用总数、按工具/状态分布。

约束：仅 `ADMIN` 角色可访问；查询范围默认 7 天、最大 90 天（可配置）。

实时指标同时通过 Actuator 暴露（`/actuator/metrics`）：

| 指标 | 说明 |
|---|---|
| `agent_task_total{capability,status}` | 任务状态流转次数 |
| `agent_task_latency_seconds{capability}` | 任务耗时直方图 |
| `agent_task_queue_depth` | 异步队列积压 |
| `agent_task_active_threads` | 执行线程数 |
| `chat_messages_total{outcome,error}` | 对话完成/失败 |
| `chat_tokens_total{direction}` | 对话 token 用量 |
| `chat_latency_seconds` | 对话耗时直方图 |
| `agent_tool_calls_total{tool,status}` | 工具调用次数 |

> 不把 `taskId` / `traceId` 作为指标标签，避免基数爆炸；Prometheus registry 作为后续可选项。

---

## 10. 附件上传（P3）

`ticket.resolve` 要求至少一张 `ONSITE` 现场照片，因此子 Agent 需要先上传：

```http
POST /agent/v1/tickets/{ticketId}/attachments?type=ONSITE
Content-Type: multipart/form-data

file=@onsite.png
```

| type | 用途 | 权限 |
|---|---|---|
| `ONSITE` | 处理人现场照片 | 必须是该工单处理人 |
| `USER` | 报修人图片 | 工单可读 |
| `GENERAL` | 一般附件 | 工单可读 |

返回附件 ID、类型、预签名 URL。上传成功后再提交 `ticket.resolve`。

使用委托令牌时，scope 需包含 `*` 或 `ticket.attachment.upload`。

附件列表（工单维度）：

```http
GET /agent/v1/tickets/{ticketId}/attachments
```

返回附件元数据与预签名 URL；委托令牌 scope 需包含 `*` 或 `ticket.detail`。附件属于工单而非任务，不伪造任务级附件表。

---

## 11. Agent Card 发现（P3/P4）

```http
GET /.well-known/agent-card.json
GET /agent-card.json
GET /agent/v1/card
```

返回 Agent 名称、描述、版本、协议版本、skills、securitySchemes 等发现信息。
P4 起 `capabilities.streaming=true`，输出模式包含 `text/event-stream`。
Card 的 `url` 由 `AGENT_CARD_URL` 配置（BFF 默认 `http://127.0.0.1:8200/agent/v1`，`agent` Profile 默认使用 `AGENT_SERVICE_PORT` 拼接）；部署到其他地址时必须显式配置，否则调用方按 Card 寻址会失败。
当前仍为 A2A 风格的最小发现端点，未实现完整 A2A 注册中心。

---

## 12. 独立 Agent 实例部署

同一构建产物支持 `agent` Profile：

```bash
# AGENT_SERVICE_PORT 必填；本项目登记 8402，不提供默认值。
AGENT_SERVICE_PORT=8402 \
AGENT_CARD_URL=http://127.0.0.1:8402/agent/v1 \
  java -jar agent-gongdan-java-bff.jar --spring.profiles.active=agent
```

- 端口必须显式配置；缺失或被占用时启动前 fail-fast，不清理占用者；
- 只暴露 `/agent/**`、`/.well-known/agent-card.json`、`/health`、`/ready`、`/actuator/health`；
- 其他用户端 API 返回 404；
- 健康/就绪：`GET /agent/v1/health`、`GET /agent/v1/ready`（数据库不可用时 503）；
- 与 BFF 共用数据库/MySQL/Redis/MinIO，属于独立运行实例，不是独立微服务；
- 启动前仍需按项目端口登记表复核端口空闲；
- Card 地址通过 `AGENT_CARD_URL` 配置为对外可达地址。

---

## 13. 调用方注意事项

1. 不要传全量聊天历史，只传任务 brief 与必要引用；
2. 不要传原始内部 Token 给最终用户；
3. 高风险能力必须展示人工确认；
4. 任务已落 `agent_task` 表，重启不丢失；异步任务当前不做进程崩溃自动恢复；
5. 不要直接调用平台内部 Controller；
6. 失败重试必须使用相同 `clientTaskId`。

---

## 13.1 下游 Agent 调用预留（P6）

当前不发起任何跨 Agent 调用，只冻结接口：

```java
public interface DownstreamAgentPort {
    boolean enabled();
    AgentInvocationResult invoke(AgentInvocation invocation);
}
```

`AgentInvocation` 只携带：

- `targetAgentId` / `taskType` / `input`；
- `contextRefs`（引用，不含聊天历史）；
- `actorId` / `roles` / `scopes`；
- `traceId` / `parentTaskId` / `correlationId` / `deadlineAt` / `budget`。

安全规则（启用前必须满足）：

1. 目标地址白名单，禁止 SSRF；
2. 使用为目标 Agent 签发的委托令牌，**不得转发 `X-Internal-Token`**；
3. 超时、熔断、重试与幂等键；
4. 全量审计 `parentTaskId` / `correlationId`；
5. 目标 Agent 自行做票级权限与数据隔离。

配置：`AGENT_DOWNSTREAM_ENABLED=false`（默认）。`GET /agent/v1/manifest` 的 `downstream.enabled` 反映该状态。

---

## 14. 示例

```bash
curl -X POST http://127.0.0.1:8200/agent/v1/tasks \
  -H "X-Internal-Token: $INTERNAL_TOKEN_P2J_CURRENT" \
  -H "X-Actor-Id: 10001" \
  -H "X-Actor-Roles: ADMIN" \
  -H "Content-Type: application/json" \
  -d '{
    "taskType": "ticket.detail",
    "input": {"ticketNo": "GD20261118000028"}
  }'
```

---

## 15. 当前限制

- 异步队列是进程内线程池，`asyncDurability=db-recovery`：启动时只重投 `PENDING`，超时 `RUNNING` 标记 `FAILED`，不保证任务不中断、不自动重放写操作；
- `deadlineAt` 只在执行前检查，不中断正在执行的任务；`budget` 仅记录，不强制中断；
- `progress` / `currentStep` 由状态派生，不是能力内部细粒度进度；
- SSE 通过数据库轮询实现，默认 1s 间隔，不是推送网关；
- `usage` 当前 `source=not-measured`；`inputTokens` / `outputTokens` / `costMicros` 字段已预留，任务执行不产生真实 LLM 计量；
- 委托令牌是内部信任模型下的 HMAC 短令牌，不是 OAuth2/OIDC 委托链；
- 当前 Task API 固定 `tenantId=0`，不接受请求传入；多租户需等可信租户声明；
- 独立 `agent` Profile 仍与 BFF 共用同一构建产物和数据库，未物理拆服务；
- 未实现完整 A2A 注册中心、任务推送回调与跨实例事件总线；
- 未提供 SDK，直接 HTTP 调用。

---

## 16. 契约稳定性

从 2026-11-20（Manifest `0.8.0`）起，以下内容视为**对外契约**，变更必须同步升级 Manifest `version` 并更新 `docs/design/07`：

- Manifest / Agent Card 必需字段与 `protocol=agent-task-v1`；
- 18 个能力名称及其 `capabilityId` / `readOnly` / `requiresConfirmation` / `executionModes` / `scopes` / `inputSchema` / `outputSchema`；
- Task 请求字段：`taskType`、`input`、`contextRefs`、`clientTaskId`、`executionMode`、`parentTaskId`、`correlationId`、`deadlineAt`、`budget`；
- Task 响应字段集合（见 `AgentTaskView`），包含 `capabilityId` / `clientTaskId` / `usage` / `progress` / `currentStep` / `cancelReason` 与上下文参数回显；
- 状态集合：`PENDING`、`RUNNING`、`WAITING_CONFIRM`、`SUCCEEDED`、`FAILED`、`CANCELLED`、`REJECTED`；
- 确认：`POST /confirm` 支持 `decision=APPROVE/REJECT`；取消支持 `reason`；
- 恢复查询：`GET /agent/v1/tasks?clientTaskId=`；
- 健康/就绪端点：`/agent/v1/health`、`/agent/v1/ready`；
- 可观测性端点：`/agent/v1/metrics/summary`；
- Manifest `limits`（`maxContextRefs` / `maxRefIdLength` / `maxInputBytes` / `maxCancelReasonLength`）、`enforcement`、`features`、`endpoints`、`envelope`、`asyncDurability`；
- 错误语义：`10001` 鉴权失败、`10003` 越权/scope 拒绝、`20001` 任务不存在、`20002` 状态冲突、`20005` 超过截止时间、`60001` 参数校验；
- 事件流：`seq` / `type`、`afterSeq`、SSE `Last-Event-ID`、`task-event` 与终态 `done`。

契约验证命令：

```bash
# Java 契约单元测试（不依赖中间件）
cd apps/java-bff
JAVA_HOME=../../.jdk PATH=../../.jdk/bin:$PATH mvn test

# 运行时契约测试（需要 Java BFF 运行）
apps/python-ai/.venv/bin/python scripts/e2e/agent_contract_test.py
```

后续按 `07-子Agent服务化改造方案与阶段目标.md` 的 Stage 5 可选拆分与持久化队列演进。
