# 11-主 Agent 接入 P0 整改评估

> 项目：agent-gongdan（Property Maintenance Agent）
> 日期：2026-11-20
> 输入：主 Agent 接入评审清单（P0 / P1 / P2）
> 性质：评估结论 + 对外契约变更记录 + 实施边界；契约以本文和 `08-Agent调用接入指南.md` 为准。

---

## 1. 结论

评审清单整体合理，**P0 全部接受并立即实施**；P1 接受其中可低成本闭环的部分，队列持久化采用“如实声明 + DB 恢复”而不是伪装成持久化 MQ；P2 中 `tenantId` 暂不接受请求侧传入，避免在缺少可信租户声明时形成越权面。

本次整改后 Manifest 版本从 `0.7.0` 升级为 **`0.8.0`**，`protocol` 保持 `agent-task-v1`，旧字段全部保留，新增字段向后兼容。

| # | 事项 | 结论 | 处理方式 |
|---|---|---|---|
| P0-1 | 端口必填、去 8400 默认、冲突 fail-fast、登记 8402 | ✅ 接受 | `AGENT_SERVICE_PORT` 必填；`agent` Profile 启动前检查端口可绑定；本项目登记 8402 |
| P0-2 | Manifest 补能力元数据与顶层元数据 | ✅ 接受 | 每项能力新增 `capabilityId`/`readOnly`/`requiresConfirmation`/`executionModes`/`scopes`/`inputSchema`/`outputSchema`；顶层新增 `features`/`endpoints`/`healthEndpoint`/`readyEndpoint`/`delegationEndpoint`/`envelope` |
| P0-3 | 补 `/health`、`/ready` | ✅ 接受 | 新增 `GET /agent/v1/health`、`GET /agent/v1/ready`；`ready` 检查数据库；独立 Profile 放行根路径 `/health`、`/ready` |
| P0-4 | confirm 支持 `decision`，增加过期 sweeper | ✅ 接受 | `APPROVE`/`REJECT`；`REJECT` 进入新终态 `REJECTED`；DB 扫描 `WAITING_CONFIRM` 过期任务并置 `FAILED` |
| P0-5 | `GET /agent/v1/tasks?clientTaskId=` | ✅ 接受 | 按令牌 Actor + `clientTaskId` 查询，供主 Agent 崩溃恢复 |
| P0-6 | 任务响应补 `agentId`/`capabilityId`/`clientTaskId`/`latencyMs`/`usage` | ✅ 接受 | 新增 `capabilityId`、`clientTaskId`、`usage`；保留原有扁平 token/cost 字段 |
| P0-7 | 事件 `seq`/`type`、`afterSeq`、SSE `Last-Event-ID` | ✅ 接受 | 事件表新增任务内 `seq` 与 `type`；`GET /events?afterSeq=`；SSE `id:` 使用 `seq` 并读取 `Last-Event-ID` |
| P1-8 | 异步队列持久化 | ⚠️ 部分接受 | 已有 `agent_task` 持久化 + 启动重投 `PENDING`；声明 `asyncDurability=db-recovery`，不实现 Redis Stream/租约 |
| P1-9 | `progress`/`currentStep`、附件列表、取消原因 | ✅ 接受（调整） | `progress`/`currentStep` 由状态派生；附件按工单维度提供列表；取消支持 `reason` 并持久化 |
| P2-10 | `tenantId` 可选 | ⏸ 暂缓 | 无可信租户声明时禁止请求侧传入，等待主 Agent/网关提供租户身份 |
| P2-11 | usage 成本 | ✅ 字段接受 | `usage` 结构先行；真实 token/成本需 LLM 计量接入后填充 |
| P2-12 | 指标标准化 | ⚠️ 文档化 | 保留现有指标名避免破坏看板；在本文与 08 固化指标契约，重命名留待下一主版本 |

**完成标志对应关系：**

- Manifest 可被机器解析 → `capabilityId` + `inputSchema`/`outputSchema` + `features` + `envelope`；
- 确认可同意/拒绝 → `decision=APPROVE/REJECT`，终态 `REJECTED`；
- 断线可重连 → `seq` + `afterSeq` + SSE `Last-Event-ID`；
- 重启不丢任务 → `agent_task` 持久化 + 启动重投 `PENDING` + `GET /tasks?clientTaskId=` 恢复查询；`RUNNING` 超时标记 `FAILED` 并给出重试提示（不自动重放写操作）。

---

## 2. P0 详细决策

### 2.1 端口

- `application-agent.yml` 改为 `server.port: ${AGENT_SERVICE_PORT}`，**删除 8400 默认值**；
- 新增启动前守卫：仅 `agent` Profile / `agent.deployment.standalone=true` 生效；
  - `AGENT_SERVICE_PORT` 缺失或占位符未解析 → 立即失败；
  - 端口已被占用 → 立即失败，提示更换端口，不尝试清理占用者；
- 本项目登记端口 **8402**（候选值经 `ss -ltn` 复核为空闲）；
- 8400 在历史报告中曾被其他项目占用，本项目不再把它作为默认值；
- 全局 `RESOURCES.md` 位于项目根目录之外，受全局边界约束，本会话不直接修改；需由用户在主目录登记 8402。项目内 `CLAUDE.md`、`README.md`、`docs/` 已同步。

### 2.2 Manifest 能力元数据

每个能力新增：

| 字段 | 含义 |
|---|---|
| `capabilityId` | 稳定机器标识，格式 `property.<capability>`，例如 `property.ticket.query` |
| `readOnly` | 是否只读；由 `mode` 派生 |
| `requiresConfirmation` | 是否需要人工确认；与旧字段 `requiresConfirm` 同值 |
| `executionModes` | 支持的执行模式；当前全部 `["SYNC","ASYNC"]` |
| `scopes` | 委托令牌可用 scope 值；当前等于能力名 |
| `inputSchema` | 输入 JSON Schema（字段、类型、必填约束） |
| `outputSchema` | `result` 载荷的 JSON Schema（Task 响应外层信封见 `envelope`） |

旧字段 `name`/`description`/`permission`/`requiresConfirm`/`mode` 保留，调用方无需立即迁移。

### 2.3 健康与就绪

| 端点 | 鉴权 | 语义 |
|---|---|---|
| `GET /agent/v1/health` | 不需要内部令牌 | 进程存活；返回 `agentId`、`version`、`status=UP` |
| `GET /agent/v1/ready` | 不需要内部令牌 | 可接流量；数据库连接可用时 200，否则 503 + `code=50004` |
| `GET /health`、`GET /ready` | 不需要内部令牌 | 既有根路径；独立 Profile 也放行 |

健康/就绪不返回数据库地址、密钥或内部拓扑。

### 2.4 确认决策与过期

```http
POST /agent/v1/tasks/{taskId}/confirm
{"confirmationToken":"...","decision":"APPROVE"}
```

- `decision` 可选，缺省 `APPROVE`，保持旧调用方兼容；
- 仅接受 `APPROVE` / `REJECT`（大小写不敏感），其他值返回 `60001`；
- `REJECT` 校验令牌与过期时间后进入终态 `REJECTED`，不执行任何业务写操作；
- 拒绝与取消都会清空确认令牌；
- 过期 sweeper 周期性扫描 `WAITING_CONFIRM` 且 `confirmation_expires_at < now` 的任务，条件更新为 `FAILED`（`errorCode=10002`），并记录 `TASK_CONFIRM_EXPIRED` 事件；
- 多实例安全：sweeper 使用 `WHERE status='WAITING_CONFIRM' AND ...` 条件更新，重复扫描不会重复处理。

状态机更新为：

```text
PENDING → RUNNING → SUCCEEDED / FAILED
PENDING / RUNNING / WAITING_CONFIRM → CANCELLED
WAITING_CONFIRM → REJECTED
WAITING_CONFIRM → FAILED（确认超时）
```

### 2.5 按 clientTaskId 恢复

```http
GET /agent/v1/tasks?clientTaskId=orchestrator-20261120-001
```

- 只能查询令牌 Actor 自己的任务；
- 命中返回与 `GET /tasks/{taskId}` 相同的 `AgentTaskView`；
- 未命中返回 `404 / 20001`，主 Agent 可据此判断是否需要重新提交；
- 提交侧仍保持幂等：相同 Actor + `clientTaskId` 重复提交返回同一任务。

### 2.6 任务响应新增字段

在保留旧字段的基础上新增：

| 字段 | 说明 |
|---|---|
| `capabilityId` | `property.<capability>` |
| `clientTaskId` | 调用方幂等键；未传时为 `null` |
| `usage` | `{inputTokens, outputTokens, costMicros, source}`；当前 `source=not-measured` |
| `progress` | 粗粒度进度：0/50/100 |
| `currentStep` | `QUEUED`/`WAITING_CONFIRM`/`EXECUTING`/`DONE`/`FAILED`/`CANCELLED`/`REJECTED` |
| `cancelReason` | 取消原因；未取消时为 `null` |

`progress`/`currentStep` 当前由任务状态派生，不代表能力内部细粒度进度；需要细粒度时由能力实现主动写事件，后续升级。

### 2.7 事件序号与断线重连

事件新增：

| 字段 | 说明 |
|---|---|
| `seq` | 任务内单调递增序号，从 1 开始；作为断线重连游标 |
| `type` | 事件类型，见下表 |

事件类型：

| type | 触发 |
|---|---|
| `TASK_QUEUED` | 进入 `PENDING` |
| `TASK_CONFIRM_REQUIRED` | 进入 `WAITING_CONFIRM` |
| `TASK_STARTED` | 进入 `RUNNING` |
| `TASK_SUCCEEDED` | 成功终态 |
| `TASK_FAILED` | 失败终态 |
| `TASK_CANCELLED` | 调用方取消 |
| `TASK_REJECTED` | 调用方拒绝确认 |
| `TASK_CONFIRM_EXPIRED` | 确认超时 |
| `TASK_STATUS_CHANGED` | 其他状态变化兜底 |

游标语义：

- `GET /agent/v1/tasks/{taskId}/events?afterSeq=N` 只返回 `seq > N` 的事件；
- SSE `id:` 字段值为 `seq`；
- 客户端重连时传 `Last-Event-ID: <seq>`，服务端从该游标之后回放；
- 若游标已越过终态事件，服务端立即发送 `done` 并关闭，不会让连接空等。

`seq` 当前由“任务内最大值 + 1”分配，并以 `(tenant_id, task_id, seq)` 唯一键兜底；并发冲突时重试。升级路径：替换为独立序列服务/Redis INCR，端口契约不变。

---

## 3. P1 决策

### 3.1 异步持久化声明

当前实现：

- 任务提交即写 `agent_task`；
- 异步队列为进程内线程池；
- 启动时重投所有 `PENDING`；
- 超过 `staleSeconds` 的 `RUNNING` 标记 `FAILED`，提示使用相同 `clientTaskId` 重试；
- 写操作不自动重放，避免重复建单/派单。

Manifest 声明：

```json
"asyncDurability": {
  "mode": "db-recovery",
  "pendingRequeuedOnRestart": true,
  "runningReplayed": false,
  "multiInstanceLease": false,
  "note": "PENDING 启动重投；RUNNING 超时置 FAILED，写操作不自动重放"
}
```

不声称 `exactly-once` 或 `no-loss`。主 Agent 对写任务仍需使用 `clientTaskId` 幂等与人工确认兜底。

### 3.2 进度、附件、取消原因

- `progress`/`currentStep`：见 2.6，状态派生；
- 附件列表：新增 `GET /agent/v1/tickets/{ticketId}/attachments`，按工单维度返回附件与预签名 URL；附件模型属于工单而非任务，不伪造任务级附件表；
- 取消原因：`POST /tasks/{taskId}/cancel` 支持可选 `{"reason":"..."}`，写入 `agent_task.cancel_reason` 并在响应中回显。

---

## 4. P2 决策

| 事项 | 结论 | 原因 |
|---|---|---|
| `tenantId` 可选字段 | 暂缓 | 当前 `agent_task` 的 `tenant_id` 固定 0，鉴权令牌没有可信租户声明；若直接接受请求传入，调用方可读取/覆盖其他租户任务。需主 Agent 在委托令牌或网关注入租户身份后再实现 |
| usage 成本 | 字段先行 | 先返回 `usage` 结构与 `source=not-measured`；真实 token/成本待 LLM 网关计量接入 |
| 指标标准化 | 文档化 | 现有 `agent_task_total`、`agent_task_latency_seconds`、`agent_task_queue_depth`、`agent_task_active_threads`、`agent_tool_calls_total` 已在用；改名会破坏看板，下一主版本统一迁移 |

---

## 5. 兼容性与风险

1. **旧字段保留**：`name`/`permission`/`requiresConfirm`/`mode`/扁平 token 字段继续返回；
2. **新增终态** `REJECTED`：调用方状态判断若使用白名单需加入该值；未识别时按非成功终态处理；
3. **SSE `id:` 语义变更**：从数据库事件 ID 改为任务内 `seq`；JSON 内仍保留原 `id` 字段。只依赖 `id:` 做游标的客户端需改为把 `id:` 当作 `seq`（这正是本次目标）；
4. **端口 8402**：主 Agent 需更新子 Agent 地址与 `AGENT_CARD_URL`；8400 不再作为默认值；
5. **全局资源登记**：`/home/admin1/Workspace/RESOURCES.md` 在项目根目录之外，本会话不修改，需用户手动登记 8402；
6. **sweeper 周期**：默认 60s，可通过 `AGENT_TASK_CONFIRM_SWEEP_MS` 调整；过期任务最多延迟一个周期才被标记。

---

## 6. 验收清单

- [ ] `AGENT_SERVICE_PORT` 缺失时 `agent` Profile 启动失败，错误信息明确；
- [ ] 端口被占用时启动失败，且不清理占用者；
- [ ] `GET /agent/v1/manifest` 返回 `version=0.8.0`，18 项能力均含 `capabilityId` 与输入/输出 schema；
- [ ] `GET /agent/v1/health`、`GET /agent/v1/ready` 可用，`ready` 在数据库不可用时返回 503；
- [ ] 写任务 `decision=REJECT` 后状态为 `REJECTED`，业务数据未落库；
- [ ] 过期 `WAITING_CONFIRM` 被 sweeper 置为 `FAILED`，令牌清空；
- [ ] `GET /tasks?clientTaskId=` 可恢复查询；
- [ ] Task 响应包含 `capabilityId`/`clientTaskId`/`usage`/`progress`/`currentStep`/`cancelReason`；
- [ ] 事件包含 `seq`/`type`，`afterSeq` 与 `Last-Event-ID` 可断线续传；
- [ ] 独立 Agent 以 8402 启动，契约测试通过；
- [ ] `mvn test` 通过，既有 API/聊天/工单功能不受影响。

---

## 7. 实施记录（2026-11-20）

### 7.1 代码与迁移

| 范围 | 文件 |
|---|---|
| 契约常量 | `app/AgentContract.java`（版本 0.8.0） |
| Manifest / 确认 / 事件 / 视图 | `app/service/AgentTaskService.java` |
| 事件序号与类型 | `app/service/AgentTaskEventService.java`、`adapters/persistence/entity/AgentTaskEventEntity.java` |
| 确认过期清理 | `app/service/AgentTaskConfirmationSweeper.java`、`config/AgentTaskConfig.java` |
| 端口守卫 | `config/AgentStandalonePortGuard.java`、`AgentGongdanApplication.java`、`application-agent.yml` |
| 健康/就绪 | `adapters/web/controller/HealthController.java`、`app/ports/health/ReadinessPort.java`、`adapters/persistence/JdbcReadinessAdapter.java` |
| Task API | `adapters/web/controller/AgentTaskController.java`、`AgentConfirmRequest`、`AgentCancelRequest`、`AgentTaskView`、`AgentTaskEventView` |
| 事件流 | `app/ports/agent/AgentTaskEventStreamPort.java`、`adapters/stream/DbPollingAgentTaskEventStreamAdapter.java` |
| 任务实体 | `adapters/persistence/entity/AgentTaskEntity.java` |
| 数据库 | `db/migration/V20__add_agent_event_seq_type_and_cancel_reason.sql` |
| 指标 | `AgentMetricsService` 终态与拒绝统计 |
| 契约测试 | `scripts/e2e/agent_contract_test.py` |

### 7.2 验证结果

- Java 单元测试：`48` 项通过（0 失败 / 0 错误），含新增 sweeper、事件 seq/type、端口守卫测试；
- 运行时契约（嵌入 BFF 8200）：`117/117` 通过；
- 运行时契约（独立 Agent 8402 + 嵌入 BFF）：`121/121` 通过；
- 端口守卫：
  - 未设置 `AGENT_SERVICE_PORT` → 启动失败，提示“必须显式配置 AGENT_SERVICE_PORT”；
  - 8402 已占用 → 启动失败，提示“端口不可用”，未执行任何清理动作；
- Flyway `V20` 在 `agent_gongdan_db` 成功执行；
- 既有用户端登录、工单列表、工单详情、AI 健康检查回归通过。

### 7.3 遗留与下一次迭代

- 异步队列仍为进程内线程池；`asyncDurability=db-recovery` 已如实声明，Redis Stream / DB Outbox / 租约待 P1；
- `progress` 为状态派生粗粒度，能力内部进度需后续事件扩展；
- `usage` 成本未接入 LLM 计量；
- `tenantId` 未开放请求传入；
- 指标重命名留给下一主版本；
- 全局 `RESOURCES.md` 的 8402 登记需用户在项目外手动完成（本会话受全局边界约束未修改）。
