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
- Phase 1 进行中：三端骨架、健康检查、Flyway V1–V10、Sa-Token JWT 认证闭环已完成（32 张业务表，0 物理外键；dev admin 登录/JWT/`/me` 已验证）；工单后端与前端最小闭环、建单幂等、审计日志、评论、MinIO 附件、知识库/RAG 检索、AI 对话（RAG 增强 + POST SSE + 消息/引用落库）、分诊与工单草稿确认（人在回路、`chat_session_ticket` 关联）、Agent 工具平台最小闭环（工具注册、只读工具、`ticket_create` 二次确认、`chat_tool_call` 轨迹）、工单筛选/派单/挂起恢复/SLA 顺延、站内通知（轮询）、历史工单向量化与相似工单检索（`ticket_similar` 工具）已接入。
- Agent 工具平台：05 附录 F 已登记演进蓝图（设计预留、代码后置）；Python 仅保留 `domain/tool.py` 与 `app/ports.py` 契约占位，不做工具实现。

## 关键约束

- 严格隔离：不触碰 `~/Workspace/` 下其他项目；共享中间件只读。
- 无物理外键、无业务 JOIN；代码层批量组装。
- MVP 妥协保留升级路径（Milvus Lite、单 worker 等）。
