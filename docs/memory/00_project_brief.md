# 00 项目简介与阶段记忆

## 项目名称与目标

**IT 运维工单智能 Agent（agent-gongdan）**：面向 IT 运维与物业场景的工单全生命周期平台，提供对话式报障、智能分诊、RAG 知识问答、相似工单与工单流转。

## 核心技术栈

- 前端：Vue3 + TypeScript + Vite + Element Plus + Axios + Pinia + Vue Router
- Java BFF：Java 17 + Spring Boot 3 + MyBatis-Plus + MySQL 8 + Sa-Token JWT
- Python AI：Python 3.13 + FastAPI + LangChain 1.x + LangGraph + Milvus Lite
- 模型：DeepSeek OpenAI 兼容；SiliconFlow `BAAI/bge-m3`（1024 维）
- 存储：MySQL `agent_gongdan_db`、Redis 前缀 `agent_gongdan:`、MinIO bucket `agent-gongdan`

## 预期核心模块

- M0 平台基础、M1 认证与用户、M2 工单、M3 派单、M4 AI 会话、M5 分诊、M6 知识库/RAG、M7 Agent 工具、M8 降级容错、M15 前端。
- P1/P2：通知、报表、业户/资源、设备/巡检、系统管理。

## 当前阶段

- Phase 0 已完成：JDK 17.0.21、Python 3.13.18、uv 0.13.8、venv、冒烟依赖、MySQL 库/账号、MinIO bucket、Milvus 目录、端口与 workerId 登记。
- Phase 1 进行中：三端骨架、健康检查、Flyway V1–V11、Sa-Token JWT 认证闭环已完成（33 张业务表，0 物理外键；dev admin 登录/JWT/`/me` 已验证）；工单后端与前端最小闭环、建单幂等、审计日志、评论、MinIO 附件、知识库/RAG 检索、AI 对话（RAG 增强 + POST SSE + 消息/引用落库）、分诊与工单草稿确认（人在回路、`chat_session_ticket` 关联）、Agent 工具平台最小闭环（工具注册、只读工具、`ticket_create` 二次确认、`chat_tool_call` 轨迹）、工单筛选/派单/挂起恢复/SLA 顺延、站内通知（轮询）、历史工单向量化与相似工单检索（`ticket_similar` 工具）、工单 AI 分析（带知识引用）、知识下载/删除、管理员一键结束、登录态 localStorage 持久化、快捷能力卡片与处理过程时间线已接入；M8 轻量降级已落地（`AiAvailabilityGuard` 自愈熔断、规则分诊兜底、前端转表单入口、`/api/v1/health/ai`），演练报告见 `docs/reports/2026-11-18-M8降级自查与演练报告.md`。
- Agent 工具平台：05 附录 F 已登记演进蓝图（设计预留、代码后置）；Python 仅保留 `domain/tool.py` 与 `app/ports.py` 契约占位，不做工具实现。

## 终极目标

- 以 `docs/design/06-终极目标与能力评估.md` 为长期能力基线：真实业务任务、完整后端工程、Agent 规划、上下文与记忆、评估与可观测、人机协同。
- 阶段目标：按 `docs/design/07-子Agent服务化改造方案与阶段目标.md`，将当前平台封装为未来大项目的 Property Maintenance Agent；P0–P4 已实现（Manifest 0.5.0、只读/写 Task API、工单生命周期写能力、确认令牌、`agent_task` 持久化、取消、任务事件、附件上传、Agent Card、SYNC/ASYNC、SSE 事件流、HMAC 委托令牌、独立 agent Profile），不重写现有业务。
- Stage 5 已完成（2026-11-19）：Java 契约单元测试、运行时契约测试（含双跑与独立实例），P1–P4 冒烟、API E2E、浏览器 E2E 全绿；契约集合已冻结。
- P6 增量升级已完成（2026-11-19）：Manifest/Agent Card 0.6.0；对话侧 `plan`/`step` 有界拆解事件与幂等工具重试；Micrometer 指标 + `/agent/v1/metrics/summary`；Task 新增并持久化 `parentTaskId`/`correlationId`/`contextRefs`/`budget`/`deadlineAt`；`DownstreamAgentPort` 预留默认禁用。
- P6.1 轻量生产适配已完成（2026-11-19）：Manifest/Agent Card 0.7.0；Card 地址 `AGENT_CARD_URL` 可配置；deadline 提交与执行前检查（过期 20005）；input 上限 65536 字节；启动恢复重投 PENDING、超时 RUNNING 标记失败；新增 `scripts/ci/verify-agent.sh` 门禁脚本。持久化 MQ、执行中超时中断、真实 token/成本回填、真实跨 Agent 调用与物理拆服务仍待后续。
- 工具执行打磨已完成（2026-11-19）：同一步骤内幂等工具并发（非幂等串行）；单工具超时按 ToolSpec 生效；Java 错误分级避免无效重试；`ticket_query` 非特权范围改为本人报障或本人处理，并支持标题/工单号关键词；`ticket_detail` 去掉重复查询。合成基准两工具 202ms → 101ms。
- 聊天写工具已完成（2026-11-19）：新增派单/接单/解决/验证/关闭/取消/挂起/恢复/结束/评论/材料/回访 12 个工具，全部二次确认；确认接口按工具映射权限并复用票级访问控制；支持工单号/数字 ID；派单前用 `user_lookup` 解析用户 ID；`AGENT_MAX_STEPS` 默认 3→5。冒烟与评测报告见 `docs/reports/2026-11-19-Agent对话与工具评测报告.md`。
- 主 Agent 接入 P0 整改已完成（2026-11-20）：Manifest/Agent Card 0.8.0；`AGENT_SERVICE_PORT` 必填并 fail-fast（本项目 8402，8400 曾被其他项目占用）；能力增加 `capabilityId`/`readOnly`/`requiresConfirmation`/`executionModes`/`scopes`/`inputSchema`/`outputSchema`；新增 `/agent/v1/health`、`/agent/v1/ready`；确认支持 `APPROVE/REJECT` 与过期 sweeper；新增 `GET /tasks?clientTaskId=`；响应增加 `usage`/`progress`/`currentStep`/`cancelReason`；事件增加 `seq`/`type`，支持 `afterSeq` 与 SSE `Last-Event-ID`。评估与实施记录见 `docs/design/11-主Agent接入P0整改评估.md`。
- 当前为 v1 可演示/可试用版本，综合评估约 5/10，后续迭代必须对齐六项能力并有量化证据。

## 关键约束

- 严格隔离：不触碰 `~/Workspace/` 下其他项目；共享中间件只读。
- 无物理外键、无业务 JOIN；代码层批量组装。
- MVP 妥协保留升级路径（Milvus Lite、单 worker 等）。
