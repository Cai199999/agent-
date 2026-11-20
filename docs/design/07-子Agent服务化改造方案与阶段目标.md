# 07-子 Agent 服务化改造方案与阶段目标

> 项目：IT 运维工单智能 Agent（agent-gongdan）
> 日期：2026-11-18
> 性质：阶段性目标与改造方案；本文只定义边界、契约、流程和验收标准，不包含业务代码。
> 关联：`06-终极目标与能力评估.md`

---

## 1. 结论

**可行，但应采用“增量封装”，不要重写当前平台。**

目标不是把当前项目改造成“某个大 Agent 的子模块”，而是把它封装成一个：

> **可被未来大项目/Orchestrator 调用的领域子 Agent 服务：Property Maintenance Agent。**

改造原则：

1. 现有 Web、Java BFF、Python AI、工单闭环保持不变；
2. 新增 Agent Facade、Task API、Manifest、确认与可观测契约；
3. 新增能力全部加开关，默认不影响现有功能；
4. 未来大项目只通过任务接口调用，不直接访问当前数据库、聊天会话或内部工具；
5. 先契约、后实现、再拆分部署。

**可行性评分：**

| 部分 | 可行性 | 说明 |
|---|---|---|
| Manifest + Task 契约 | 高 | 纯新增，无业务侵入 |
| 同步 Task Facade | 高 | 复用现有 Chat/Ticket/Knowledge 服务 |
| 异步任务与确认令牌 | 中高 | 需新增任务状态表与确认生命周期 |
| 上下文/权限/Actor 契约 | 高 | 现有 Sa-Token、dataScope、审计可复用 |
| 可观测与评测 | 中 | 现有 traceId/日志可扩展，指标聚合需补齐 |
| 独立部署拆分 | 中 | 建议最后做，不阻塞前四步 |
| **整体** | **高（增量）** | 不影响现有功能，可分阶段上线 |

---

## 2. 目标定位

### 2.1 当前项目

当前项目同时包含：

- 物业工单 OA 内核（Java BFF + MySQL）；
- AI 对话与 Agent 工具（Python AI）；
- Web 管理界面；
- 知识库、导出、材料、回访、审核。

### 2.2 未来角色

未来大项目中，当前项目作为：

```text
Property Maintenance Agent
  = 物业报修 / 工单 / 派单 / 现场 / 回访 / 知识 / 导出 领域子 Agent
```

它不负责：

- 全局任务规划；
- 跨领域多 Agent 编排；
- 财务、HR、采购等非物业运维领域；
- 大项目统一身份系统；
- 大项目统一报表中心。

它负责：

- 接收领域任务；
- 在自身权限与审计边界内执行；
- 返回结构化结果、引用、artifact 与人工确认请求；
- 保留自己的数据库、知识库、工具与评测。

---

## 3. 目标架构

```text
未来大项目 / Orchestrator
  │
  │  Agent Manifest + Task API
  │  actor / tenant / dataScope / traceId / confirmationToken
  ▼
Agent Facade（Java BFF 内新增薄层）
  │
  ├─ AgentTaskService（任务状态机）
  ├─ AgentCapabilityRegistry（能力→现有服务）
  ├─ AgentContext（任务上下文）
  └─ AgentConfirmationService（高风险确认）
        │
        ▼
现有业务内核（不改）
  ├─ ChatService / Triage / Ticket / Knowledge
  ├─ 权限 / 审计 / 幂等 / SLA
  └─ MySQL / MinIO / Milvus
```

**边界原则：**

- Orchestrator 不直连 MySQL / MinIO / Milvus；
- Orchestrator 不直接调用现有 ChatController；
- Python 仍保持无状态，不直接对外暴露为子 Agent；
- Java BFF 是子 Agent 的对外门面与业务写入方；
- 高风险确认仍由当前平台完成。

---

## 4. 完整改造流程

### Stage 0：契约冻结与基线

**目标：** 先定义“子 Agent 长什么样”，不动代码。

产出：

- `Agent Manifest` v1；
- `Task API` 契约；
- `Context / Result / Error / Confirmation` 契约；
- 能力清单：`ticket.create`、`ticket.query`、`ticket.dispatch`、`ticket.resolve`、`knowledge.search`、`ticket.export` 等；
- 现有 API 基线：64 个 Java 映射、10 个 Python 路由、API E2E 22 项、浏览器 E2E 4 项。

**验收：**

- Manifest 可以被外部读懂；
- 每个 capability 有输入/输出/权限/是否需确认；
- 不影响任何现有接口。

---

### Stage 1：Agent Manifest 与只读能力

**目标：** 新增只读入口，验证契约。

新增：

```http
GET /agent/v1/manifest
GET /agent/v1/capabilities
POST /agent/v1/tasks        （仅支持只读任务）
GET /agent/v1/tasks/{id}
```

首批能力：

- `ticket.query`
- `ticket.detail`
- `knowledge.search`
- `ticket.similar`

**实现方式：**

- Facade 复用现有 `TicketService` / `KnowledgeService` / `TicketVectorService`；
- 不经过聊天 SSE，直接返回结构化结果；
- 任务只读，不写业务数据；
- 不新增数据库表，先使用内存任务状态（可选）。

**验收：**

- 现有 E2E 全量通过；
- 新 Task API 可返回工单/知识结果；
- 不影响 ChatController。

---

### Stage 2：任务状态与写操作

**目标：** 支持建单、派单、解决、导出等任务。

新增表（建议）：

```text
agent_task
  id, tenant_id, task_no, agent_id, task_type, status,
  actor_id, actor_roles, data_scope, input_json, context_refs_json,
  result_json, error_code, trace_id, cost, latency_ms,
  confirmation_id, idempotency_key,
  created_at, updated_at, finished_at
```

任务状态：

```text
PENDING → RUNNING → WAITING_CONFIRM → SUCCEEDED / FAILED / CANCELLED
```

写操作：

- `ticket.create` / `ticket.export` 默认 `WAITING_CONFIRM`；
- 返回确认卡片数据 + `confirmation_id`；
- 确认接口：

```http
POST /agent/v1/tasks/{id}/confirm
```

- 确认人必须与 actor 一致，或具备管理员权限；
- 确认令牌短时效、一次性；
- 确认后调用现有 `TicketService` / `TicketExportJobService`。

**验收：**

- 未确认不会写库/生成文件；
- 确认后与现有 UI 行为一致；
- 现有工单 E2E 不受影响。

---

### Stage 3：上下文与 Actor 契约

**目标：** 明确“什么能传、什么不能传”。

Task 请求只允许：

```json
{
  "taskId": "...",
  "taskType": "ticket.create",
  "actor": {
    "tenantId": 0,
    "actorId": 10001,
    "roles": ["ADMIN"],
    "permissions": ["ticket:create"],
    "dataScope": "ALL"
  },
  "input": {},
  "contextRefs": [],
  "traceId": "...",
  "confirmationToken": null
}
```

禁止：

- 全量聊天历史；
- 其他租户/用户数据；
- 原始内部 Token；
- 绕过 dataScope 的实体 ID 列表。

**验收：**

- 子 Agent 仍做票级权限校验；
- 越权返回 403；
- 审计记录 actor 与 taskId。

---

### Stage 4：异步、事件与可观测

**目标：** 支持长任务和外部编排。

- 任务异步执行；
- `GET /agent/v1/tasks/{id}` 查询状态；
- 可选 `GET /agent/v1/tasks/{id}/events` SSE；
- 记录：
  - traceId
  - taskId
  - agentId
  - capability
  - 耗时
  - token / 成本
  - 工具调用
  - 降级
  - 人工确认
  - 成功/失败原因

**验收：**

- 任务可按 taskId 追踪；
- 可统计成功率、P95、成本；
- 失败可重试/取消。

> P4 状态：异步任务、SSE 事件、耗时/状态追踪、取消已落地；token/成本字段已预留但未接真实计量，持久化 MQ 与崩溃恢复未完成。

---

### Stage 5：契约测试与可选拆分

**目标：** 保证未来大项目接入稳定。

- Manifest 契约测试；
- Task API 契约测试；
- 确认令牌测试；
- 越权测试；
- 现有 API/浏览器 E2E 回归；
- 双跑：现有 UI 与 Task API 同时可用。

可选拆分：

- 将 Agent Facade 抽为独立 `apps/agent-service`；
- 当前 Java BFF 仍为业务内核；
- 拆分前提：调用量、故障隔离或团队边界明确。

> P5 状态（2026-11-19）：契约测试已落地（Java 单元 25 项 + 运行时 78 项，含双跑与独立实例）；**物理拆服务仍未做**，当前独立实例只是同一构建产物的 `agent` Profile。

---

## 5. 不影响现有功能的保证

1. **只做加法**
   - 新增 `/agent/v1/**`，不修改现有 `/api/v1/**` 行为。

2. **Feature Flag**
   - `AGENT_SERVICE_ENABLED=false` 时完全不注册 Facade 路由/任务执行器。

3. **双跑与回归**
   - 每个 Stage 结束都运行：
     - API E2E 22 项；
     - 浏览器 E2E 4 项；
     - AI 评测基线 12 条。

4. **数据库只增不删**
   - 新表 `agent_task` 等只新增；
   - 不修改现有工单表结构；
   - 无物理外键、无业务 JOIN。

5. **权限不绕过**
   - 子 Agent 复用现有 Sa-Token、票级权限、dataScope、审计；
   - 外部 actor 不能扩大权限。

6. **人工确认不绕过**
   - 建单/导出仍走现有确认逻辑；
   - 外部确认必须携带有效令牌。

---

## 6. 风险与应对

| 风险 | 应对 |
|---|---|
| ChatService 与聊天会话耦合 | Facade 优先调用 Ticket/Knowledge 服务；需要对话时再适配 ChatService |
| 同步任务超时 | Stage 4 引入异步任务与状态查询 |
| 确认语义与 UI 不一致 | 复用现有 tool-call/triage 确认逻辑，抽统一确认服务 |
| 外部 Orchestrator 越权 | actor + dataScope + 票级权限 + 审计 |
| 任务重复执行 | idempotency_key + 任务唯一键 |
| 现有功能被影响 | 只加路由、Feature Flag、双跑回归 |
| 过早拆服务 | Stage 5 前不拆；先 Facade |

---

## 7. 阶段性目标与退出标准

| 阶段 | 目标 | 退出标准 |
|---|---|---|
| Stage 0 | 契约冻结 | Manifest/API/Context/Result 文档评审通过 |
| Stage 1 | 只读子 Agent | 只读 Task API 可用；现有 E2E 全绿 |
| Stage 2 | 写操作 + 确认 | 建单/导出确认链路可用；无未确认写入 |
| Stage 3 | 权限与上下文 | 越权 403；审计完整；上下文不外泄 |
| Stage 4 | 异步与可观测 | taskId 可追踪；成功率/耗时/成本可统计 |
| Stage 5 | 契约稳定与可选拆分 | 契约测试通过；可独立部署 |

**当前建议：先做 Stage 0 + Stage 1，不动业务代码，只加门面和只读能力。**

---

## 8. 非目标

- 不把当前项目重写为微服务；
- 不现在就做多 Agent 协作；
- 不让未来 Orchestrator 直接访问数据库；
- 不共享当前聊天上下文；
- 不绕过当前权限与人工确认；
- 不在 Stage 0–2 阶段引入复杂 Planner。

---

## 9. P0 实现记录（2026-11-18）

已完成 **Stage 0 + Stage 1 的 P0 只读版本**，未修改现有业务接口。

### 9.1 新增接口

```http
GET  /agent/v1/manifest
GET  /agent/v1/capabilities
POST /agent/v1/tasks
GET  /agent/v1/tasks/{taskId}
```

鉴权：复用内部令牌 `X-Internal-Token`，Actor 通过 `X-Actor-Id` / `X-Actor-Roles` 传递。
路由级拦截：`/agent/**` 与 `/internal/**` 使用同一内部令牌拦截器。

### 9.2 首批只读能力

- `ticket.query`
- `ticket.detail`（支持数字 ID 或工单号）
- `ticket.similar`
- `knowledge.search`

### 9.3 当前实现边界

- 任务状态保存在内存，服务重启丢失；P1 落 `agent_task` 表；
- 仅只读，不写业务数据；
- 不支持确认、异步、流式事件；
- `clientTaskId` 支持幂等重放；
- 未做独立部署，仍在 Java BFF 内。

### 9.4 P0 验证

```bash
apps/python-ai/.venv/bin/python scripts/e2e/agent_p0_smoke.py
```

结果：

```text
passed=6 failed=0 total=6
```

覆盖：Manifest、ticket.query、GET task、ticket.detail by ticketNo、knowledge.search、未知能力拒绝。

### 9.5 P1 实现记录（2026-11-18）

已完成 P1 核心写能力与确认：

- `V16__create_agent_task_table.sql`：任务持久化；
- `POST /agent/v1/tasks/{taskId}/confirm`；
- `ticket.create` / `ticket.export`：先 `WAITING_CONFIRM`，确认后执行；
- 确认令牌：10 分钟、一次性、Actor 校验；
- 任务按 `taskId` 持久化，重启不丢失；
- `clientTaskId` 幂等。

验证：

```bash
apps/python-ai/.venv/bin/python scripts/e2e/agent_p1_smoke.py
# passed=4 failed=0 total=4
```

仍未做：

- 异步任务/事件流；
- 取消；
- 成本字段；
- 独立部署；
- 更多写工具（派单/解决/材料/回访）。

### 9.6 P2 实现记录（2026-11-18）

- Manifest 升级至 `0.3.0`；
- 新增写能力：
  - `ticket.assign` / `accept` / `resolve` / `verify` / `close` / `cancel`
  - `ticket.hold` / `resume` / `finish`
  - `ticket.comment.add` / `ticket.material.add` / `ticket.visit.add`
- 所有写能力 `requiresConfirm=true`；
- 新增 `POST /agent/v1/tasks/{taskId}/cancel`；
- 写操作复用现有 `TicketService` / `TicketAccessService` / 材料/回访服务；
- 票级权限、状态机、审计不变。

验证：

```bash
apps/python-ai/.venv/bin/python scripts/e2e/agent_p2_smoke.py
# passed=7 failed=0 total=7
```

仍未做：异步任务、事件流、token/成本、独立部署、附件上传工具。

### 9.7 P3 实现记录（2026-11-18）

- Manifest / Agent Card 升级至 `0.4.0`；
- 新增附件上传：`POST /agent/v1/tickets/{ticketId}/attachments?type=ONSITE|USER|GENERAL`，打通“上传现场照片 → `ticket.resolve`”闭环；
- `ONSITE` 上传复用 `TicketAccessService.requireHandler`，其余类型要求可读；
- 新增任务事件查询：`GET /agent/v1/tasks/{taskId}/events`，记录 `WAITING_CONFIRM` / `SUCCEEDED` / `FAILED` / `CANCELLED` 等生命周期；
- `V17__add_agent_task_observability_and_events.sql`：
  - `agent_task` 增加 `input_tokens`、`output_tokens`、`cost_micros`、`execution_mode`；
  - 新增 `agent_task_event`；
- 新增 A2A 风格发现端点：
  - `GET /.well-known/agent-card.json`
  - `GET /agent-card.json`
  - Agent Card 暴露 skills、securitySchemes，不暴露密钥；
- 任务响应增加 `inputTokens` / `outputTokens` / `costMicros` / `executionMode`；
- 新增 `scripts/e2e/agent_p3_smoke.py`。

验证：

```bash
apps/python-ai/.venv/bin/python scripts/e2e/agent_p3_smoke.py
# card + create + assign + accept + attachment + resolve + events
```

**P3 仍属“同步任务 + 事件历史”，不是完整异步事件流：**

- 无独立任务队列 / Worker；
- 无 SSE / WebSocket 推送，`events` 为落库历史查询；
- token / cost 字段已预留，当前任务执行不产生真实 LLM 计量，值为空或 0；
- 未独立部署，仍与 BFF 同进程；
- 未实现完整 A2A 协议注册/发现中心与委托鉴权，仅提供 Agent Card 形状的发现端点；
- 浏览器 E2E 未覆盖“附件上传 → 解决”全链路。

### 9.8 P4 实现记录（2026-11-18）

目标：把子 Agent 从“同步接口”推进到“可异步、可订阅、可委托、可独立运行”的服务形态。

已完成：

- Manifest / Agent Card 升级至 `0.5.0`；
- 异步任务：
  - `AgentTaskRequest.executionMode` 支持 `SYNC` / `ASYNC`；
  - 新增 `AgentTaskQueuePort` + `AgentTaskJobHandler`；
  - 新增进程内适配器 `LocalAgentTaskQueue`（`agentTaskExecutor` 线程池）；
  - 新增 `AgentTaskWorker`：`PENDING → RUNNING → SUCCEEDED/FAILED`，原子领取 + 条件落终态；
  - 确认后的写任务也可异步执行；
  - 队列/线程池参数外置，未来替换 Redis Stream / MQ 只需换端口适配器。
- SSE 任务事件流：
  - `GET /agent/v1/tasks/{taskId}/events/stream`；
  - 先回放历史事件，再增量推送；终态发送 `done` 并关闭；
  - 15s 心跳、可配置超时；
  - 新增 `AgentTaskEventStreamPort`，适配器 `DbPollingAgentTaskEventStreamAdapter` 以事件表轮询实现，多实例可共享；
  - 保留 `GET /tasks/{id}/events` 历史查询兼容。
- 委托鉴权：
  - `POST /agent/v1/delegation/token` 签发短时 HMAC 令牌；
  - Header `X-Delegation-Token`；
  - Actor / 角色 / scope 由令牌声明，存在委托令牌时忽略可伪造的 `X-Actor-*`；
  - `taskType` 与附件上传都校验 scope；
  - 委托令牌不能再次委托，避免权限扩散；
  - `V18__add_agent_task_delegation.sql` 记录 `delegation_id` 便于审计。
- 独立部署形态：
  - 新增 `agent` Spring Profile + `AgentStandaloneModeFilter`；
  - 独立实例只暴露 `/agent/**`、Agent Card、`/actuator/health`，用户端 API 返回 404；
  - 使用 `--spring.profiles.active=agent`、`AGENT_SERVICE_PORT` 启动；
  - 仍复用同一构建产物与数据库，属于“独立运行实例”，尚未拆成独立微服务仓库。
- 浏览器 E2E：
  - 新增 `ticket-lifecycle.spec.ts`：建单 → 派单 → 接单 → 上传现场照片 → 解决 → 待验证。

验证：

```bash
apps/python-ai/.venv/bin/python scripts/e2e/agent_p4_smoke.py
# 19 checks：异步只读/写、SSE 回放+增量、委托 scope、Actor 绑定、确认令牌清理
cd apps/web && npm run e2e
# 5 checks：认证、对话、个人中心、建单、完整生命周期
```

**P4 仍保留的边界：**

- 队列是进程内线程池，不是持久化 MQ；进程崩溃时 `PENDING/RUNNING` 任务不会自动恢复；
- SSE 通过数据库轮询实现，默认 1s 间隔，不是推送网关；
- token / cost 字段仍为预留，未接真实 LLM 计量；
- 委托令牌是内部信任模型下的 HMAC 短令牌，不是 OAuth2/OIDC 委托链；
- 独立实例仍复用同一 jar 和数据库，未完成物理拆库/拆服务；
- 未实现完整 A2A 注册中心、任务推送回调与跨实例事件总线。

### 9.9 Stage 5 实现记录（2026-11-19）

目标：冻结契约并用测试固化，保证未来 Orchestrator 接入稳定，同时验证“双跑”和独立实例不破坏现有功能。

已完成：

- **Java 契约单元测试（25 项，无中间件依赖）**：
  - `AgentTaskServiceTest`（16 项）：Manifest/Agent Card 契约、能力集合与写确认标记、SYNC/ASYNC、`clientTaskId` 幂等、非法 executionMode、委托 scope、Actor 隔离、确认令牌错误/过期/单次使用、异步确认入队、取消语义；
  - `HmacDelegationTokenAdapterTest`（6 项）：签发/校验、防篡改、过期、issuer/Actor 校验、TTL 钳制到 [60, 3600]、密钥过短未启用；
  - `InternalTokenInterceptorTest`（3 项）：内部令牌必需、Actor 头解析、委托令牌覆盖可伪造 Actor 头。
- **运行时契约测试** `scripts/e2e/agent_contract_test.py`（78 项，含可选独立实例）：
  - Manifest / Agent Card 字段与版本一致性、18 个能力集合冻结、写能力 `requiresConfirm=true`；
  - 鉴权：缺失/错误内部令牌、非法委托令牌、委托 Actor 绑定、scope 拒绝、禁止二次委托；
  - Task API：响应字段集合、traceId 透传、`clientTaskId` 按 Actor 幂等、未知能力 `FAILED/60001`、非法模式与空 taskType 400；
  - 确认令牌：错误令牌、非所有者、单次使用、取消后失效、未确认不落库；
  - 事件与 SSE：事件字段、状态顺序 `PENDING→RUNNING→SUCCEEDED`、事件 ID 递增、`done` 终止、非所有者 403；
  - 双跑：`/api/v1` 登录、工单列表、确认建单后的工单详情、AI 健康检查；
  - 独立实例：manifest/card 可用、用户端 API 返回 404、`/actuator/health` 正常。
- **回归验证**：
  - Java `mvn test`：25/25；
  - 运行时契约：78/78（含独立实例 4 项）；
  - Agent P1–P4 冒烟：4/4、7/7、7/7、19/19；
  - 原有 API E2E：22/22；
  - 浏览器 E2E：5/5。
- 契约冻结规则：Manifest `version` 与能力集合变更必须同步升级版本并更新本文件与 `agent_contract_test.py`；新增能力默认只读，写能力必须 `requiresConfirm=true`。

**Stage 5 仍保留的边界：**

- 契约测试是运行时黑盒测试，不是 CI 门禁；尚未接入流水线；
- 独立实例仍复用同一 jar、数据库和进程内队列，未做物理拆服务/拆库；
- 队列、SSE 轮询、token/成本等 P4 已知边界不变；
- 未引入 Pact 等消费者驱动契约工具，当前以 Manifest + 字段集合冻结为主。

### 9.10 P6 增量升级（2026-11-19）

目标：在不改变现有功能的前提下，提升任务拆解与可观测性，并预留跨 Agent 传参。

已完成：

- **任务拆解（有界）**：
  - 对话侧新增 `plan` / `step` SSE 事件，暴露每步工具与耗时；
  - 幂等只读工具瞬时失败最多重试 1 次，非幂等工具不重试；
  - `AGENT_PLAN_EVENTS`、`AGENT_TOOL_RETRY_MAX` 可配置。
- **可观测性**：
  - 新增 `MetricsPort` + `MicrometerMetricsAdapter`：任务状态/耗时、队列深度、对话 token/耗时、工具调用；
  - 新增 `GET /agent/v1/metrics/summary`（ADMIN）：成功率、P95、状态/能力分布、token 与工具调用；
  - 复用既有 `chat_message` token/耗时字段，不新增采集表。
- **跨 Agent 传参预留**：
  - Task 新增 `parentTaskId` / `correlationId` / `deadlineAt` / `budget`，`contextRefs` 持久化；
  - `V19` 增加上下文列与索引，无物理外键；
  - 新增 `DownstreamAgentPort` 与禁用适配器，默认 `AGENT_DOWNSTREAM_ENABLED=false`；
  - Manifest / Agent Card 升至 `0.6.0`。
- **验证**：
  - Java 契约单元测试 27/27；
  - Python 拆解/重试测试 3/3；
  - 运行时契约 93/93（含独立实例）；
  - 原有 API E2E 25/25；Agent P1–P4 4/7/7/19 全通过；浏览器 E2E 5/5。

**P6 仍保留的边界：**

- `deadlineAt` / `budget` 只记录，不强制中断；
- 指标聚合实时查库，未接指标库/Prometheus；
- 下游 Agent 端口仅冻结契约，未发起真实调用；
- 无通用 Planner、无多 Agent 编排。

### 9.11 P6.1 轻量生产适配（2026-11-19）

目标：做接入前必要的生产适配，不引入新中间件、不拆服务。

已完成：

- Agent Card `url` 改为 `AGENT_CARD_URL` 配置，BFF 默认 8200、agent Profile 默认 8400；
- `deadlineAt` 轻量生效：提交时拒绝过期；Worker 执行前复查，过期任务 `FAILED` + `20005`，不写业务数据；不中断执行中任务；
- Manifest 新增 `enforcement`，明确 `budget` 仅记录、`runtimeTimeout` 不强制；
- `input` 默认上限 65536 字节（`AGENT_TASK_MAX_INPUT_BYTES`），超限 400/60001；
- 新增启动恢复 `AgentTaskRecoveryRunner`：重投 `PENDING`，超时 `RUNNING` 标记 `FAILED`（50004），不自动重放写操作；
- 新增 `scripts/ci/verify-agent.sh` 轻量门禁脚本；
- Manifest / Agent Card 升至 `0.7.0`。

验证：

- Java 34/34（新增 deadline、输入上限、启动恢复测试）；
- Python 3/3；
- 原有 API E2E、Agent P1–P4、浏览器 E2E 回归口径不变。

仍保留的边界：

- 队列仍为进程内线程池；恢复只保证“不永久悬挂”，不保证任务不中断；
- `deadlineAt` 不中断执行中任务，`budget` 不强制；
- 指标聚合实时查库，无看板/告警；成本未回填；下游 Agent 未真实调用。

### 9.12 工具执行打磨（2026-11-19）

目标：从“平台治理”转向“工具本身”，先解决影响成功率与延迟的问题。

已完成：

- 同一 LLM 步骤内，全部为幂等工具时并发执行；含非幂等工具则串行，事件顺序保持不变；
- 单工具超时按 `ToolSpec.timeout_seconds` 生效，HTTP 客户端 10s 仅兜底；
- Java 内部错误分级为 `tool_forbidden` / `tool_not_found` / `tool_invalid_arguments` / `tool_failed`，仅瞬时错误与超时重试；
- `ticket_query` 非特权范围改为“本人报障或本人处理”，关键词同时匹配标题与工单号；
- `ticket_detail` 复用已加载实体，去掉重复查询。

验证：

- Python 14/14（新增并发、超时、错误分级、参数校验测试）；
- Java 34/34；API E2E 25/25；
- 合成基准：两个各 100ms 的工具，串行 202ms → 并发 101ms，降低约 50%。

### 9.13 聊天写工具与确认（2026-11-19）

目标：把 `/agent/v1` 已有的工单写能力接入对话工具，保持人在回路。

已完成：

- 新增 12 个聊天写工具：派单、接单、解决、验证、关闭、取消、挂起、恢复、结束、评论、材料、回访；
- 全部 `requiresConfirm=true`，只生成待确认草稿；确认后由 Java 执行；
- 确认接口按工具名映射权限（`ticket:assign` / `ticket:handle` / `ticket:verify` / `ticket:close` / `ticket:read`），并复用票级访问控制；
- 支持工单号/数字 ID；派单前用 `user_lookup` 解析用户 ID；
- 确认增加 `PENDING → RUNNING` 领取锁；权限拒绝统一 `403/10003`；
- `AGENT_MAX_STEPS` 默认 3 → 5。

验证：

- Python 23/23、Java 34/34；
- `scripts/eval/write_tool_smoke.py`：assign、hold、resume、comment、close、material、visit 全部通过；
- `scripts/eval/write_tool_permission_smoke.py`：4/4 通过；
- 评测报告见 `docs/reports/2026-11-19-Agent对话与工具评测报告.md`。

仍保留：导出 `open_only`/处理人筛选、列表批量返回姓名、聚合分析工具、草稿 TTL 与版本快照。

### 9.14 主 Agent 接入 P0 整改（2026-11-20）

目标：让 Manifest 可机器解析、确认可同意/拒绝、断线可重连、重启不丢任务。

已完成：

- **端口**：`AGENT_SERVICE_PORT` 转为必填，删除 8400 默认值；新增启动前端口守卫，缺失或被占用时 fail-fast；本项目登记独立 Agent 端口 `8402`（8400 曾被其他项目占用）；
- **Manifest 0.8.0**：每项能力新增 `capabilityId`（`property.*`）、`readOnly`、`requiresConfirmation`、`executionModes`、`scopes`、`inputSchema`、`outputSchema`；顶层新增 `features`、`endpoints`、`healthEndpoint`、`readyEndpoint`、`delegationEndpoint`、`envelope`、`asyncDurability`；
- **健康/就绪**：新增 `GET /agent/v1/health`、`GET /agent/v1/ready`；ready 检查数据库连接，不可用返回 503；独立 Profile 放行根路径 `/health`、`/ready`；
- **确认**：`confirm` 支持 `decision=APPROVE/REJECT`；拒绝进入新终态 `REJECTED`；新增确认过期 sweeper，将超时 `WAITING_CONFIRM` 条件更新为 `FAILED`；
- **恢复**：新增 `GET /agent/v1/tasks?clientTaskId=`，按 Actor + 幂等键查询；
- **响应**：新增 `capabilityId`、`clientTaskId`、`usage`、`progress`、`currentStep`、`cancelReason`；保留旧字段；
- **事件**：新增任务内 `seq` 与 `type`；`GET /events?afterSeq=`；SSE `id:` 使用 `seq` 并支持 `Last-Event-ID`；
- **P1 补充**：取消支持 `reason`；新增工单附件列表；`asyncDurability=db-recovery` 如实声明启动重投 PENDING、不重放 RUNNING。

验证：

- Java 单测通过（含新增 sweeper、事件 seq/type、端口守卫测试）；
- `agent_contract_test.py`：嵌入 BFF 117/117、独立 Agent 8402 121/121；
- 端口缺失与端口占用均验证 fail-fast。

详见 `11-主Agent接入P0整改评估.md`。

> 9.11 / 9.12 中“默认 8400”的描述已被本节取代；8400 不再作为本项目默认端口。

---

## 10. 结论

**该改造可行，且风险可控。**

关键是：

> 先把当前平台封装成“有 Manifest、有 Task API、有权限、有确认、有审计”的领域子 Agent，而不是改造成“大 Agent 的一个内部模块”。

建议从 **Stage 0（契约冻结）+ Stage 1（只读 Task API）** 开始，保持现有功能完全不变，再逐步进入写操作、异步和可选拆分。
