# 09-任务拆解、可观测性与跨 Agent 传参升级

> 注：2026-11-20 主 Agent 接入 P0 整改后，独立 Agent 端口改为必填 `AGENT_SERVICE_PORT`（本项目 8402），Manifest 升至 `0.8.0`；本文中的 `8400` / `0.7.0` 为当时记录，最新契约见 `11-主Agent接入P0整改评估.md` 与 `08-Agent调用接入指南.md`。

> 项目：IT 运维工单智能 Agent（agent-gongdan）
> 日期：2026-11-19
> 性质：增量升级说明 + 接入方法；不改变现有 `/api/v1/**` 行为。
> 关联：`07-子Agent服务化改造方案与阶段目标.md`、`08-Agent调用接入指南.md`

---

## 1. 升级目标

在**不改变现有功能**的前提下补齐三项基础能力：

1. **简单任务拆解**：对话侧暴露有界计划与步骤事件，只对幂等工具做有限重试；
2. **可观测性**：任务/对话/工具调用的成功率、P95、token 与队列指标可查询；
3. **跨 Agent 传参预留**：Task API 增加父子任务、关联 ID、上下文引用、预算与截止时间，并冻结下游调用端口。

明确不做：

- 通用自主 Planner / 多 Agent 编排；
- 长期记忆；
- 真实跨 Agent 网络调用；
- 物理拆服务、持久化 MQ、OAuth2/OIDC 委托链。

---

## 2. 兼容性声明

| 变化 | 影响 | 兼容策略 |
|---|---|---|
| Manifest / Agent Card `0.5.0 → 0.6.0` | 契约版本变化 | 旧字段不变；新增字段全部可选 |
| Task 请求新增 4 个可选字段 | 无 | 缺省为 null，旧调用方无需修改 |
| Task 响应新增 5 个字段 | 无 | 旧客户端忽略未知字段 |
| SSE 新增 `plan` / `step` 事件 | 仅对话流 | 旧前端 `default: break` 自动忽略 |
| 工具失败重试 | 仅幂等只读工具 | 成功路径不变；重试有上限 |
| 新增指标与聚合端点 | 只读 | 不影响现有接口 |
| 新增 `DownstreamAgentPort` | 无 | 默认禁用，不发请求 |

> 现有 API E2E、浏览器 E2E、Agent P1–P4 冒烟在升级后全部通过。

---

## 3. 任务拆解（有界计划）

### 3.1 现状与边界

对话侧的 Python 工具循环最多 `AGENT_MAX_STEPS` 步（2026-11-19 起默认 5，写作/多工具链路需要更多步骤）。本次升级不引入新的 Planner 模型，只把“已经发生的拆解”显式化，并提高失败恢复能力。

### 3.2 新增 SSE 事件

**plan**：在每步执行工具前下发。

```json
{
  "step": 1,
  "max_steps": 3,
  "actions": [
    {"tool": "ticket_query", "arguments_summary": "状态=全部，关键词=无，条数=5"}
  ]
}
```

**step**：每个工具执行完成后下发。

```json
{
  "step": 1,
  "action_index": 0,
  "tool": "ticket_query",
  "status": "SUCCESS",
  "attempts": 1,
  "duration_ms": 16,
  "error_code": null
}
```

说明：

- 事件由 Python 产生，Java BFF 原样转发；前端未识别的旧版本会自动忽略；
- `status` 取值：`SUCCESS` / `FAILED` / `CONFIRM_REQUIRED`；
- 工具参数非法时不执行工具，`attempts=1`，`error_code=tool_invalid_arguments`。

### 3.3 工具重试策略

只对**幂等工具**的**瞬时失败**重试：

- 幂等工具：`ticket_query` / `ticket_detail` / `ticket_similar` / `knowledge_search` / `ticket_export`；
- 非幂等工具：`ticket_create` 不重试，避免重复建单；
- 仅 `error_code=tool_failed` / `tool_timeout` 重试；参数错误、权限错误、确认态错误不重试；
- Java 内部错误已分级：`tool_forbidden` / `tool_not_found` / `tool_invalid_arguments` 不重试，避免无效请求；
- 最大重试次数 = `min(AGENT_TOOL_RETRY_MAX, ToolSpec.max_retries)`，默认 1 次；
- 每次重试写 `chat.tool_retry` 日志，`step` 事件携带 `attempts`。

### 3.4 配置

| 变量 | 默认 | 说明 |
|---|---|---|
| `AGENT_MAX_STEPS` | 5 | 工具循环上限；写作链路需要“解析人员 + 执行动作”多步 |
| `AGENT_PLAN_EVENTS` | true | 是否下发 `plan` 事件；`step` 事件始终下发 |
| `AGENT_TOOL_RETRY_MAX` | 1 | 幂等工具最大重试次数 |
| `TOOL_RESULT_MAX_CHARS` | 4000 | 工具结果截断上限 |

### 3.5 前端接入（可选）

当前前端忽略 `plan` / `step`。若要展示处理过程：

```ts
// apps/web/src/api/chat.ts
case "plan":
  handlers.onPlan?.(data);
  break;
case "step":
  handlers.onStep?.(data);
  break;
```

建议展示为“第 N 步：调用工具 X，耗时 Yms，尝试 Z 次”。

### 3.6 工具执行性能（2026-11-19）

| 优化 | 行为 |
|---|---|
| 并发执行 | 同一步骤内**全部为幂等工具**时用 `asyncio.gather` 并发；出现 `ticket_create` 等非幂等工具则整体串行 |
| 事件顺序 | 并发结果按原始 `action_index` 顺序输出，`tool_call` / `step` 事件顺序不变 |
| 单工具超时 | 以 `ToolSpec.timeout_seconds` 为准，通过 `asyncio.wait_for` 生效；HTTP 客户端超时仅作兜底 |
| 错误分级 | Java 4xx/业务码映射为 `tool_forbidden` / `tool_not_found` / `tool_invalid_arguments`；仅瞬时错误与超时重试 |
| 查询口径 | `ticket_query` 非特权角色可见“本人报障或本人处理”的工单；关键词同时匹配标题与工单号 |
| 详情查询 | `ticket_detail` 复用已加载实体，去掉一次重复 `selectById` |

实测（假网关，两个各 100ms 的工具）：串行 202ms，并发 101ms，**降低约 50%**；事件顺序保持 `[0, 1]`。

### 3.7 聊天写工具与二次确认（2026-11-19）

对话侧新增 12 个写工具：

| 工具 | 参数 | 确认后动作 | 所需权限 |
|---|---|---|---|
| `ticket_assign` | ticket_id、assignee_id、reason | 派单/改派 | `ticket:assign` |
| `ticket_accept` | ticket_id、reason | 接单 | `ticket:handle` |
| `ticket_resolve` | ticket_id、service_item_text、onsite_situation | 提交解决 | `ticket:handle` |
| `ticket_verify` | ticket_id、approved、reason | 验证 | `ticket:verify` |
| `ticket_close` | ticket_id、close_reason_code、reason | 关闭 | `ticket:close` |
| `ticket_cancel` | ticket_id、cancel_reason | 取消 | `ticket:close` |
| `ticket_hold` / `ticket_resume` | ticket_id、reason | 挂起/恢复 | `ticket:handle` |
| `ticket_finish` | ticket_id、reason | 结束 | `ticket:close` |
| `ticket_comment_add` | ticket_id、content、visibility | 评论 | `ticket:read` |
| `ticket_material_add` | ticket_id、material_name 等 | 材料 | `ticket:handle` |
| `ticket_visit_add` | ticket_id、visit_method 等 | 回访 | `ticket:close` |

规则：

- 工具只返回 `CONFIRM_REQUIRED` 草稿，未确认不修改任何工单；
- 确认接口按工具名做权限映射，并复用 `TicketAccessService` 的票级访问控制；
- 支持工单号或数字 ID；派单前用 `user_lookup` 解析用户 ID；
- 确认增加 `PENDING → RUNNING` 领取锁：并发确认只有一个能执行，其余返回已完成或冲突；
- 权限拒绝统一返回 `403 / 10003`（全局异常处理补 `NotPermissionException`）；
- 确认成功后写 `SUCCESS` 与 `resultTicketId`；重复确认直接返回详情；
- `AGENT_MAX_STEPS` 默认调整为 5，覆盖“解析人员 + 执行动作”的多步链路。

前端新增通用“工单操作确认”卡片，覆盖全部写工具；确认后刷新为“已执行”。`npm run typecheck` 与 `npm run build` 通过。

冒烟验证：
- `scripts/eval/write_tool_smoke.py`：assign、hold、resume、comment、close、material、visit 全部通过；
- `scripts/eval/write_tool_permission_smoke.py`：engineer 接单通过、engineer 派单拒绝、reporter 关闭拒绝、reporter 评论通过。

---

## 4. 可观测性

### 4.1 聚合端点

```http
GET /agent/v1/metrics/summary?from=<ISO8601>&to=<ISO8601>
X-Internal-Token: ...
X-Actor-Id: 10001
X-Actor-Roles: ADMIN
```

响应结构：

```json
{
  "from": "...",
  "to": "...",
  "agentTasks": {
    "total": 198,
    "active": 0,
    "terminal": 198,
    "successRate": 0.8737,
    "byStatus": {"SUCCEEDED": 173, "FAILED": 11, "CANCELLED": 14},
    "byCapability": {"ticket.query": 52, "ticket.create": 55},
    "p95LatencyMs": 334,
    "latencySamples": 184,
    "inputTokens": 0,
    "outputTokens": 0,
    "costMicros": 0
  },
  "chat": {
    "assistantMessages": 118,
    "failed": 18,
    "inputTokens": 226768,
    "outputTokens": 21690,
    "avgLatencyMs": 2789,
    "p95LatencyMs": 4694,
    "latencySamples": 118
  },
  "toolCalls": {
    "total": 58,
    "byTool": {"ticket_query": 13, "ticket_create": 27},
    "byStatus": {"SUCCESS": 51, "FAILED": 7}
  }
}
```

规则：

- 仅 `ADMIN` 可访问；
- 默认最近 7 天，最大 90 天；
- P95 采用最近 N 条样本在应用内计算（默认 10000），后续可替换为指标库；
- `successRate` 分母为终态任务（`SUCCEEDED+FAILED+CANCELLED`）。

### 4.2 实时指标（Actuator）

`/actuator/metrics`：

| 指标 | 标签 | 用途 |
|---|---|---|
| `agent_task_total` | `capability`、`status` | 任务状态流转 |
| `agent_task_latency_seconds` | `capability` | 任务耗时直方图 |
| `agent_task_queue_depth` | 无 | 异步队列积压 |
| `agent_task_active_threads` | 无 | 执行线程数 |
| `chat_messages_total` | `outcome`、`error` | 对话完成/失败 |
| `chat_tokens_total` | `direction` | token 用量 |
| `chat_latency_seconds` | 无 | 对话耗时 |
| `agent_tool_calls_total` | `tool`、`status` | 工具调用 |

实现：`MetricsPort`（应用端口）+ `MicrometerMetricsAdapter`（适配器）。
未来接 Prometheus 只需增加 `micrometer-registry-prometheus` 依赖，无需改业务代码。

### 4.3 成本

- 对话 token 已在 `chat_message.token_in/token_out` 持久化；
- 任务表已预留 `input_tokens/output_tokens/cost_micros`；
- 当前任务能力大多不调用 LLM，成本为 0；后续 LLM 型任务在分发器回填即可；
- 单价必须配置化，禁止硬编码。

### 4.4 配置

| 变量 | 默认 | 说明 |
|---|---|---|
| `AGENT_METRICS_MAX_RANGE_DAYS` | 90 | 聚合查询最大范围 |
| `AGENT_METRICS_LATENCY_SAMPLE_LIMIT` | 10000 | P95 最大样本数 |

---

## 5. 跨 Agent 传参预留

### 5.1 Task 请求新增字段

```json
{
  "taskType": "ticket.query",
  "input": {"limit": 5},
  "contextRefs": {"ticketId": "1001", "kind": "TICKET"},
  "parentTaskId": "upstream-task-001",
  "correlationId": "flow-20261119-001",
  "deadlineAt": "2026-11-19T12:00:00+08:00",
  "budget": {"maxTokens": 1000, "maxLatencyMs": 10000}
}
```

| 字段 | 类型 | 约束 | 当前行为 |
|---|---|---|---|
| `parentTaskId` | string | ≤64 | 持久化、回显、审计关联 |
| `correlationId` | string | ≤64 | 持久化、回显 |
| `contextRefs` | object | ≤50 项 | 持久化；只传引用 |
| `budget` | object | 无 | 持久化，不强制中断 |
| `deadlineAt` | ISO 8601 | 合法时间 | 持久化，不强制中断 |

### 5.2 数据库

`V19__add_agent_task_context_and_limits.sql`：

- `parent_task_id`、`correlation_id`、`context_refs_json`、`budget_json`、`deadline_at`；
- 3 个普通索引；
- **无物理外键、无级联操作**。

### 5.3 下游调用端口

```text
app/ports/agent/AgentInvocation.java
app/ports/agent/AgentInvocationResult.java
app/ports/agent/DownstreamAgentPort.java
adapters/agent/DisabledDownstreamAgentAdapter.java
```

默认 `AGENT_DOWNSTREAM_ENABLED=false`：

- `enabled()` 返回 false；
- `invoke()` 抛出 `SYS_DEPENDENCY_UNAVAILABLE`；
- 不发起任何网络请求。

启用真实调用前必须补齐：

1. 目标 Agent 白名单；
2. 使用委托令牌而非内部令牌；
3. 超时/熔断/重试/幂等键；
4. 审计 `parentTaskId` & `correlationId`；
5. 目标端权限与数据隔离。

### 5.4 接入方如何使用

1. 读取 `/agent/v1/manifest`，确认 `version=0.6.0`、`contextSchema`、`capabilities`；
2. 生成 `correlationId`，贯穿整条调用链；
3. 只把必要引用放入 `contextRefs`，禁止传完整聊天历史；
4. 写任务携带 `confirmationToken` 完成确认；
5. 通过 `/agent/v1/metrics/summary` 观察成功率与 P95，按 `correlationId` 做日志关联。

---

## 6. 测试与验收

### 6.1 新增测试

| 测试 | 数量 | 说明 |
|---|---|---|
| Java 契约单元测试 | 27 | 原 25 + 上下文参数/非法 deadline |
| Python 拆解与重试测试 | 3 | plan/step、幂等重试、非幂等不重试 |
| 运行时契约测试 | 93 | 新增上下文参数、指标端点、版本 0.6.0 |
| 原有 API E2E | 25 | 新增 plan/step 事件检查 |
| 浏览器 E2E | 5 | 回归 |

### 6.2 命令

```bash
# Java 单元测试
cd apps/java-bff
JAVA_HOME=../../.jdk PATH=../../.jdk/bin:$PATH mvn test

# Python 拆解/重试测试
cd apps/python-ai
./.venv/bin/python -m unittest discover -s tests -v

# 运行时契约（需要 Java BFF）
apps/python-ai/.venv/bin/python scripts/e2e/agent_contract_test.py
AGENT_STANDALONE_BASE=http://127.0.0.1:8400 \
  apps/python-ai/.venv/bin/python scripts/e2e/agent_contract_test.py

# 原有回归
apps/python-ai/.venv/bin/python scripts/e2e/e2e_api_smoke.py
for s in agent_p1_smoke agent_p2_smoke agent_p3_smoke agent_p4_smoke; do
  apps/python-ai/.venv/bin/python scripts/e2e/$s.py
done
cd apps/web && npm run e2e
```

### 6.3 验收标准

- 现有 `/api/v1/**` 行为与响应不变；
- `plan` / `step` 事件可观测，成功路径无额外失败；
- 非幂等工具绝不重试；
- 指标端点返回合理成功率/P95/token；
- Task 请求缺少新字段时行为与 0.5.0 完全一致；
- 独立 `agent` Profile 仍只暴露 `/agent/**` 与健康端点。

---

## 7. 已知边界与后续

- `deadlineAt` / `budget` 仅记录，不强制中断；
- 指标聚合实时查库，适合中小数据量，未接指标库；
- 下游 Agent 端口未实现真实 HTTP 调用；
- 未接入 Prometheus / OpenTelemetry；
- 未做多 Agent 编排与统一注册中心；
- 契约版本变更需同步更新 `07`、`08`、`agent_contract_test.py` 与 Manifest。

---

## 8. P6.1 轻量生产适配（2026-11-19）

不引入新中间件、不拆服务，只补接入前最容易出问题的几项：

| 项 | 变化 |
|---|---|
| Card 地址 | `url` 改为 `AGENT_CARD_URL` 配置；BFF 默认 8200，`agent` Profile 默认 8400 |
| deadline | 提交时拒绝已过期；Worker 执行前再次检查，过期任务 `FAILED` + `20005`，不写业务数据；不中断执行中任务 |
| budget | 明确为 `recorded-only`，Manifest `enforcement.budget.enforced=false` |
| 输入上限 | `input` 序列化后默认最大 65536 字节，超限 400/60001；Manifest `limits.maxInputBytes` |
| 启动恢复 | 启动时重投 `PENDING`；超过 `stale-seconds` 的 `RUNNING` 标记 `FAILED`（错误码 50004），不自动重放写操作 |
| 门禁 | 新增 `scripts/ci/verify-agent.sh`：Java 单元测试 + Python 单元测试；设置 `AGENT_BASE` 后追加运行时契约 |

配置：

| 变量 | 默认 | 说明 |
|---|---|---|
| `AGENT_CARD_URL` | `http://127.0.0.1:8200/agent/v1`（agent Profile 8400） | Card 对外地址 |
| `AGENT_TASK_DEADLINE_ENABLED` | true | 是否执行 deadline 检查 |
| `AGENT_TASK_MAX_INPUT_BYTES` | 65536 | 单任务 input 上限 |
| `AGENT_TASK_RECOVERY_ENABLED` | true | 启动恢复开关 |
| `AGENT_TASK_RECOVERY_STALE_SECONDS` | 300 | RUNNING 判定为中断的阈值 |
| `AGENT_TASK_RECOVERY_BATCH_SIZE` | 100 | 每批恢复条数 |

多实例注意：PENDING 重投靠 Worker 原子领取，多实例安全；RUNNING 判定依赖时间阈值，多实例部署时阈值必须大于正常任务最长执行时间，或关闭恢复器。

契约版本升至 `0.7.0`；验证：Java 34 项单元测试、Python 3 项、原有回归不变。仍保留：进程内队列、SSE 轮询、budget 不强制、成本未回填、下游 Agent 未真实调用。
