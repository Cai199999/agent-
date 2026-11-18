# E2E 半自动化操作清单

> 项目：IT 运维工单智能 Agent（agent-gongdan）
> 日期：2026-11-18
> 目标：前端人工操作 + API 自动检查点，覆盖核心闭环。
> 自动化脚本：`scripts/e2e/e2e_api_smoke.py`

---

## 1. 运行方式

```bash
# 三端已启动后执行
apps/python-ai/.venv/bin/python scripts/e2e/e2e_api_smoke.py
```

脚本覆盖：

- 登录 admin
- 创建 AI 会话
- 对话分诊 / 草稿确认建单
- 工单详情
- 工单 AI 分析
- 派单 → 接单 → 解决 → 验证 → 关闭
- 知识检索 / 下载

预期输出：

```text
passed=17 failed=0 total=17
```

> 脚本只调用 Java BFF，不直连 MySQL/Redis/MinIO；不修改共享中间件。

---

## 2. 前端人工操作 + API 检查点

### 2.1 登录与权限

| 步骤 | 前端操作 | API 检查点 | 预期 |
|---|---|---|---|
| 1 | 打开 `http://127.0.0.1:5180/`，输入 admin + `.env` 密码 | `POST /api/v1/auth/login` | 200，返回 accessToken |
| 2 | 刷新页面 | `GET /api/v1/auth/me` | 仍登录，返回权限列表 |
| 3 | 用 `engineer1` 登录，地址栏访问 `/knowledge` | `GET /api/v1/knowledge/docs` | 403，前端提示“您没有权限执行该操作（追踪号：...）” |

### 2.2 对话报修与建单

| 步骤 | 前端操作 | API 检查点 | 预期 |
|---|---|---|---|
| 4 | AI 助手 → 新建 → 发送 `A栋2号梯困人了，请尽快处理` | `POST /api/v1/chat/sessions`、`POST /api/v1/chat/sessions/{id}/messages` | SSE `triage` + `ticket_card` + `done` |
| 5 | 点击“确认建单” | `POST /api/v1/chat/triage-records/{id}/confirm` | 200，返回 `ticketNo`、`statusCode=PENDING` |
| 6 | 工单管理搜索该工单号 | `GET /api/v1/tickets?keyword=...` | 能查到，状态“待受理” |

### 2.3 工单 AI 分析

| 步骤 | 前端操作 | API 检查点 | 预期 |
|---|---|---|---|
| 7 | 打开工单详情 → “开始 AI 分析” | `POST /api/v1/tickets/{id}/ai-analysis` | 200，返回 summary/causes/actions/riskLevel |
| 8 | 再次打开详情 | `GET /api/v1/tickets/{id}/ai-analysis` | 返回最近一次分析 |
| 9 | 点击“查看推荐方案 →” | `GET /api/v1/tickets/{id}`（页面加载） | 跳转 AI 助手并预填问题 |

### 2.4 派单与处理

| 步骤 | 前端操作 | API 检查点 | 预期 |
|---|---|---|---|
| 10 | admin 点击“派单”，选择张工 | `GET /api/v1/users/options`、`POST /api/v1/tickets/{id}/assign` | 状态 `PENDING_ACCEPT` |
| 11 | engineer1 登录 → “接单” | `POST /api/v1/tickets/{id}/accept` | 状态 `PROCESSING` |
| 12 | engineer1 “解决”，填写处理结果 | `POST /api/v1/tickets/{id}/resolve` | 状态 `PENDING_VERIFY` |
| 13 | admin “验证通过” | `POST /api/v1/tickets/{id}/verify` | 状态 `RESOLVED` |
| 14 | admin “关闭” | `POST /api/v1/tickets/{id}/close` | 状态 `CLOSED` |
| 15 | 打开工单时间线 | `GET /api/v1/tickets/{id}` | 含 CREATED/ASSIGN/ACCEPT/RESOLVE/VERIFY/CLOSE |

### 2.5 知识库与 RAG

| 步骤 | 前端操作 | API 检查点 | 预期 |
|---|---|---|---|
| 16 | 知识库 → 填写标题/分类，上传 `e2e-elevator.md` | `POST /api/v1/knowledge/docs` | 返回 `docNo`、`chunkCount ≥ 1` |
| 17 | 知识检索输入 `电梯困人怎么处理` | `POST /api/v1/knowledge/search` | 命中文档，返回 score |
| 18 | AI 助手提问 `电梯困人怎么处理？` | `POST /api/v1/chat/sessions/{id}/messages` | 回答含 citation；点击引用 Tag 可下载 |
| 19 | 文档列表点击“下载” | `GET /api/v1/knowledge/docs/{id}/download` | 返回预签名 URL |
| 20 | 删除测试文档，再次检索 | `DELETE /api/v1/knowledge/docs/{id}`、`POST /api/v1/knowledge/search` | 不再命中被删除文档 |

### 2.6 通知与附件

| 步骤 | 前端操作 | API 检查点 | 预期 |
|---|---|---|---|
| 21 | 派单/接单/解决/关闭后查看铃铛 | `GET /api/v1/notifications/unread-count` | 未读数增加 |
| 22 | 打开通知中心，单条已读/全部已读 | `PUT /api/v1/notifications/{id}/read`、`PUT /api/v1/notifications/read-all` | 未读数减少 |
| 23 | 工单详情上传附件 | `POST /api/v1/tickets/{id}/attachments` | 附件列表出现，可删除 |

---

## 3. 自动化覆盖边界

| 能力 | 是否自动覆盖 |
|---|---|
| 登录、会话、SSE、分诊、建单 | ✅ |
| 工单详情、AI 分析 | ✅ |
| 派单/接单/解决/验证/关闭 | ✅ |
| 知识检索/下载 | ✅ |
| 前端视觉、拖拽、滚动、弹窗样式 | ❌ 需人工 |
| 通知铃铛、附件 UI | ❌ 需人工 |
| M8 断网/停 Python 降级 | ❌ 建议按 E2E 指南人工执行 |

---

## 4. 失败记录模板

```text
时间：
步骤编号：
前端操作：
API：
实际结果：
预期结果：
traceId：
截图/日志：
```

---

## 5. 注意

- 脚本会创建真实工单并推进到 CLOSED，请在 dev 环境运行；
- 不要在生产库执行；
- 知识删除仅删除测试文档，不要删除既有知识；
- 不修改共享 MySQL/Redis/MinIO 容器。
