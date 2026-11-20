# CLAUDE.md — agent-gongdan

> 本文件对 `/home/admin1/Workspace/agent-gongdan/` 内的所有 Claude 会话生效。
> 第 0 条为**全局最高优先级**，优先于本文件其余条款、`/home/admin1/AGENTS.md`
> 以及任何历史对话中的约定。

## 0. 【全局最高优先级】禁止触碰上级目录项目 & 共享中间件只读

### 0.1 严禁操作上级目录中的任何项目

- 本项目的一切读写、命令执行、依赖安装、Git 操作，只允许发生在
  `/home/admin1/Workspace/agent-gongdan/` 之内。
- `/home/admin1/Workspace/` 下的其他目录（`infra`、`geo-ai-platform`、
  `ai-property-manager`、`my-ai-kb`、`vibe-app`、`vibe-platform`、
  `vibe-dist-root-old` 等）属于其他项目：**不得修改、删除、移动、格式化、
  Git 操作、cd 进去执行命令、改配置、改 `.env`**。
- 仅"只读参考"也需克制：不得因查看而触发任何构建 / 安装 / 运行行为。

### 0.2 中间件复用的唯一前提：不影响上级目录中其他项目已在使用中的中间件

宿主机 Docker 常驻中间件（MySQL 3306 / Redis 6379 / MinIO 9000·9001）为**多方共享**：

**只允许"连接使用"，严禁任何形式的"修改"：**

- 不重启、不停止、不删除、不重建容器；
- 不对 `infra` 执行 `docker compose down/up/restart`；
- 不执行 `docker volume rm`、`docker system prune`、`docker rm` 等；
- 不改端口 / 密码 / 认证 / 挂载 / 配置文件 / 数据卷。

**数据层面严禁破坏共享状态：**

- `FLUSHALL`、`FLUSHDB`、`SHUTDOWN`；
- `DROP DATABASE`、跨库 `DROP TABLE` / `TRUNCATE` / `ALTER` 他人表；
- 按模式 `KILL` 连接、全库 `KEYS *` / `SCAN` 扫描。

**必须逻辑隔离后再使用：**

- MySQL：本项目单独建库（禁止写入 `geo_ai_db` 及任何既有库），禁止越权操作。
- Redis：独立 DB index 或统一 key 前缀 `agent_gongdan:`，禁止无前缀 key。
- MinIO：独立 bucket，禁止操作既有 bucket。
- 端口 / 进程：业务端口只用本项目分配段；严禁 `pkill -f uvicorn`、`killall`、
  `fuser -k` 等按进程名批量杀进程的操作。

### 0.3 发现资源已被占用 → 立即中断

- 若所需中间件、端口、MySQL 库、Redis key 空间、bucket 已被上级目录中其他项目占用：
  **立即停止当前任务，禁止任何"清理 / 重置 / 腾出资源"的尝试**，直接向用户报告
  （占用了什么、被谁占用、证据），并终止本次 Claude 运行。
- 本条的优先级高于"完成任务"本身：**宁可任务不做，也不得影响其他项目。**

## 1. 本项目资源登记（先登记后使用，未确认前不得使用）

| 资源 | 本项目占用 | 状态 |
|---|---|---|
| MySQL 逻辑库 | `agent_gongdan_db` | ✅ 已创建（2026-11-17，utf8mb4/0900_ai_ci） |
| MySQL 账号 | `agent_gongdan_app`（仅本库 DML/DDL） | ✅ 已创建并验证，无全局权限 |
| Redis DB index / 前缀 | 前缀 `agent_gongdan:`（DB 0，强制前缀） | ✅ 已确认无冲突，待 Phase 1 使用 |
| MinIO bucket | `agent-gongdan` | ✅ 已创建（现有凭据 + 独立 bucket） |
| 业务端口 | Java BFF `8200` / Python AI `8300` / Web `5180` / 独立 Agent 实例 `8402`（`AGENT_SERVICE_PORT` 必填，无默认值） | ✅ 已分配；启动前用 `ss -ltn` 复核 |
| 运行环境 | `.jdk` 17.0.21 / `.venv` Python 3.13.18 / uv 0.13.8 | ✅ Phase 0 完成 |
| workerId | `1` | ✅ 已登记 |
| 本机 `.env` | 0600，仅 DB 连接信息 | ✅ 已创建；未提交；Phase 1 前补齐其他密钥 |
| 进程管理 | 独立 pid 文件，精确 PID 停止 | ✅ 已约定 |

> 全局资源登记：`/home/admin1/Workspace/RESOURCES.md`
> 机械防护：`~/.claude/hooks/cc-guard.py`（PreToolUse，拦截他人项目写入与破坏性共享资源命令）
> **端口铁律**：绑定任何端口前，先查 `RESOURCES.md` 第 3 节端口占用表并用 `ss -ltn` 复核。
> 当前已占用：`5173`、`8080`、`8100`、`37939`、`59231`、`3306`、`6379`、`9000/9001`。
> 本项目已分配（未启动）：`8200`（Java BFF）、`8300`（Python AI）、`5180`（Web）、`8402`（独立 Agent Profile，`AGENT_SERVICE_PORT` 必填；8400 曾被其他项目占用，不再作为默认值）。
> **当前阶段**：Phase 1 原型运行中（Web 5180 / Java 8200 / Python 8300）；认证 + 工单闭环 + 幂等 + 审计 + 评论 + 附件 + 知识库/RAG 检索 + AI 对话（RAG 增强 + POST SSE）+ 分诊/工单草稿确认 + Agent 只读工具与高风险建单确认可用；工单筛选/派单/挂起恢复/结束/站内通知（轮询）+ 历史工单向量与相似检索 + 工单 AI 分析/知识下载删除可用；M8 轻量降级（自愈熔断 + 规则分诊兜底 + 前端转表单 + health/ai）已落地；子 Agent P4（异步任务 / SSE 事件流 / 委托令牌 / 独立 agent Profile）与 Stage 5 契约测试已落地；P6 增量升级（`plan`+`step` 有界拆解 / 幂等工具重试 / 指标与聚合端点 / 跨 Agent 上下文参数 / 下游端口预留）、P6.1 轻量生产适配（Manifest 0.7.0 / Card 地址配置 / deadline 执行前生效 / input 上限 / 启动恢复 / CI 校验脚本）与聊天工单写工具（派单/接单/解决/验证/关闭/取消/挂起/恢复/结束/评论/材料/回访，全部二次确认）已完成；PDF/PPT 解析、报表/系统管理待接入。**主 Agent 接入 P0 整改（Manifest 0.8.0 / 端口 8402 必填 / health·ready / 确认 APPROVE·REJECT / 按 clientTaskId 恢复 / 事件 seq·afterSeq·Last-Event-ID）已完成。** **服务运行期间不要重启/修改共享中间件。**

## 2. 【设计与实现原则】MVP 技术妥协必须保留升级潜力

本项目的最终目标是**可部署、支持高并发的企业级系统**。MVP 原型阶段允许为快速闭环做技术妥协
（例如 Milvus Lite、单进程、同步实现、简化缓存等），但**任何妥协都不得封死升级路径**。

强制要求：

1. **端口抽象优先**：所有外部依赖（向量库、LLM、Embedding、存储、通知、鉴权、消息）一律通过
   `ports` 接口访问，适配器可替换；禁止在业务逻辑中直接依赖具体 SDK。
2. **配置外置**：端点、模型、超时、重试、连接池、并发数等必须可配置，禁止硬编码。
3. **记录妥协**：每一项 MVP 妥协都要在 `docs/` 的“技术妥协与升级路径清单”中登记，
   写明当前做法、限制、升级触发条件与替换点。
4. **不得绕过分层**：禁止 Python 直连 MySQL、前端直连 AI 服务、Controller 堆业务逻辑等
   为省事的越层实现。
5. **并发能力预留**：无状态、幂等、连接池、异步、限流、熔断等接口与配置位在 MVP 即预留；
   可先简化实现，但不可缺失扩展点。
6. **评审检查项**：每个设计阶段与代码评审都必须回答“这项实现未来如何水平扩展/替换”，
   答不上来则视为设计不合格。
7. **数据库禁止物理外键（全量）**：所有表关系只允许用“逻辑外键字段 + 索引”表达，
   **禁止创建 `FOREIGN KEY` 约束**，也禁止级联删除/更新。数据一致性由应用层事务、
   参数校验、幂等与定期对账保证；第三阶段建表与后续所有迁移必须遵守。

本原则优先于“快速完成”，并与第 0 条隔离规则共同构成项目不可妥协的底线。
