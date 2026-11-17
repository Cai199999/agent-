# IT 运维工单智能 Agent（agent-gongdan）

可部署、支持高并发演进的企业级 IT 运维工单智能 Agent。

## 技术栈

- 前端：Vue3 + TypeScript + Vite + Element Plus（5180）
- Java BFF：Java 17 + Spring Boot 3 + MyBatis-Plus + Sa-Token JWT（8200）
- Python AI：Python 3.13 + FastAPI + LangChain/LangGraph + Milvus Lite（8300）
- 数据：MySQL（`agent_gongdan_db`）、Redis（前缀 `agent_gongdan:`）、MinIO（bucket `agent-gongdan`）

## 设计文档

- `docs/design/01-需求与功能模块清单.md`
- `docs/design/02-技术选型与架构设计.md`
- `docs/design/03-数据库设计文档.md`
- `docs/design/04-API接口设计清单.md`
- `docs/design/05-最终设计方案与开发规范.md`

## 环境约定

- JDK 17 位于项目内 `.jdk/`，不修改系统默认。
- Python 3.13 虚拟环境位于 `apps/python-ai/.venv/`（uv 管理）。
- Milvus Lite 数据位于 `data/milvus/`。
- 端口：Java 8200、Python 8300、Web 5180。
- 共享中间件只读使用；不修改/重启容器；不影响其他项目。
- `.env` 不提交；从 `.env.example` 复制并填写本地密钥。

## 本地启动（后续阶段完善）

```text
# Python
apps/python-ai/.venv/bin/uvicorn main:app --host 127.0.0.1 --port 8300

# Java（在 apps/java-bff 下）
JAVA_HOME=../../.jdk mvn spring-boot:run

# Web
cd apps/web && npm run dev
```

> 当前仅 Phase 1 骨架；业务接口与环境脚本在后续迭代补充。
