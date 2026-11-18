# 08-Agent 调用接入指南

> 项目：IT 运维工单智能 Agent（agent-gongdan）
> 日期：2026-11-18
> 适用对象：未来大项目 / Orchestrator / 其他 Agent
> 当前版本：P2（Manifest 0.3.0 + 只读/写任务 + 确认令牌 + 任务持久化 + 取消 + 工单生命周期写能力）

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
  "version": "0.1.0",
  "protocol": "agent-task-v1",
  "taskEndpoint": "/agent/v1/tasks",
  "auth": {
    "type": "internal-token",
    "header": "X-Internal-Token"
  },
  "capabilities": [
    {
      "name": "ticket.query",
      "description": "查询当前 Actor 可见工单",
      "permission": "ticket:read",
      "requiresConfirm": false,
      "mode": "READ_ONLY"
    }
  ]
}
```

调用方应缓存 Manifest，并按 `version` 处理契约变化。

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
  "contextRefs": {},
  "clientTaskId": "orchestrator-20261118-001"
}
```

响应：

```json
{
  "code": 0,
  "data": {
    "taskId": "4ab2050f-...",
    "agentId": "property-maintenance",
    "capability": "ticket.query",
    "status": "SUCCEEDED",
    "result": [],
    "errorCode": null,
    "errorMessage": null,
    "traceId": "...",
    "latencyMs": 12,
    "createdAt": "2026-11-18T08:00:00Z"
  }
}
```

`clientTaskId` 用于幂等：同一 Actor 重复提交相同 `clientTaskId` 返回相同任务。

---

## 5. 查询任务

```http
GET /agent/v1/tasks/{taskId}
```

只能查询同一 `X-Actor-Id` 创建的任务。

---

## 6. P0 能力

| capability | input | 说明 |
|---|---|---|
| `ticket.query` | `status`、`keyword`、`limit` | 查询当前 Actor 可见工单 |
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
| 业务校验失败 | `FAILED` + 对应错误码 |

调用方应记录 `taskId`、`traceId`、`errorCode`。

---

## 8. 写能力与人工确认（P1 已实现）

```text
POST /agent/v1/tasks
  → status=WAITING_CONFIRM
  → 返回 confirmationToken + 摘要
  → 用户人工确认
  → POST /agent/v1/tasks/{id}/confirm
  → SUCCEEDED / FAILED
```

确认：

```http
POST /agent/v1/tasks/{taskId}/confirm
Content-Type: application/json

{"confirmationToken":"..."}
```

规则：

- 未确认绝不写库/生成文件；
- 确认令牌 10 分钟、一次性；
- 只能由同一 Actor 确认；
- 确认后仍走现有工单/导出服务；
- 任务已落 `agent_task` 表，重启不丢失。

当前已支持：

- 建单、导出；
- 派单、接单、解决、验证、关闭、取消、挂起、恢复、结束；
- 评论、材料、回访。

取消：

```http
POST /agent/v1/tasks/{taskId}/cancel
```

仅 `PENDING/RUNNING/WAITING_CONFIRM` 可取消。

---

## 9. 调用方注意事项

1. 不要传全量聊天历史，只传任务 brief 与必要引用；
2. 不要传原始内部 Token 给最终用户；
3. 高风险能力必须展示人工确认；
4. 不要依赖任务内存状态：P0 重启会丢失，P1 落库；
5. 不要直接调用平台内部 Controller；
6. 失败重试必须使用相同 `clientTaskId`。

---

## 10. 示例

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

## 11. 当前限制

- 无异步任务队列/事件流；
- 未记录 token/成本字段；
- 未独立部署；
- 未提供 SDK，直接 HTTP 调用；
- 写能力目前只有建单/导出。

后续按 `07-子Agent服务化改造方案与阶段目标.md` 的 Stage 2–5 演进。
