# 04-API接口设计清单

> 项目：**IT 运维工单智能 Agent**（agent-gongdan）
> 阶段：第四阶段 · API 设计
> 版本：v1.1
> 日期：2026-11-17
> 状态：待评审（已按安全/SSE/错误码/权限清单修订）
> 说明：本文档只包含 API 契约与设计，**不包含任何业务代码、控制器实现或脚手架代码**。示例 JSON 均为契约示例。

---

## 1. 文档信息与决策记录

### 1.1 修订记录

| 版本 | 日期 | 修订人 | 说明 |
|---|---|---|---|
| v1.0 | 2026-11-17 | Claude | 基于 01/02/03 定稿，输出公共 API、内部 API、SSE、错误码与鉴权设计 |
| v1.1 | 2026-11-17 | Claude | 安全补全（CSRF、refresh 轮转、方向拆分内部令牌）；SSE 与 02 对齐；错误码全量对照；权限三向表；opaque cursor；附件 Q6 闭环；回填 02 §7.2/§7.3/§6.2 |
| v1.2 | 2026-11-18 | Claude | Phase 1 实现对账：补 `GET /api/v1/users/options`、`GET /api/v1/health/ai` 与 2 个内部接口；新增 §4.10 实现状态与差异说明；05 状态更新为已确认 |

### 1.2 关联文档

| 阶段 | 文档 | 状态 |
|---|---|---|
| 第一阶段 | `01-需求与功能模块清单.md` | 已认可 |
| 第二阶段 | `02-技术选型与架构设计.md`（v1.1） | 已回填 |
| 第三阶段 | `03-数据库设计文档.md`（v1.1） | 已确认 |
| 第四阶段 | `04-API接口设计清单.md` | 本文档 |
| 第五阶段 | `05-最终设计方案与开发规范.md`（v1.2） | 已确认 |

### 1.3 本阶段决策

| 编号 | 事项 | 决策 |
|---|---|---|
| D36 | JWT 策略（Q3） | access token 存前端内存；refresh token 为 `HttpOnly + Secure + SameSite=Lax` Cookie，并配 CSRF token；**Sa-Token 显式 JWT 无状态模式**；refresh 强制轮转、旧 refresh 进黑名单一次性使用；Redis 不可用时 refresh 接口 fail-closed，access token 仍按无状态有效至过期 |
| D37 | 服务间认证（Q4） | **按方向拆分为两把内部 Token**：`Java→Python` 与 `Python→Java` 各一把；`current + next` 双 Token 并行轮换，轮换确认后旧 Token 失效 |
| D38 | 流式协议 | MVP 仅保留**对话 POST SSE**；通知/SLA 预警 GET SSE 降为 **P1**，不进入 MVP 详细契约 |
| D39 | API 命名与版本 | 公共 `/api/v1`，内部 `/internal/v1`；公共 camelCase，Java↔Python 内部 snake_case |
| D40 | AI 关键接口 | 相似工单、分诊、建单溯源均以 `vector_object` / `triage_record` / `source_ref_*` 为基础 |
| D41 | 幂等 | 建单、确认分诊、知识入库支持 `Idempotency-Key`；重复请求返回首次结果；幂等重放不是错误 |
| D42 | 错误码 | 32 个公共数字码 + HTTP 状态；内部 snake_case code 必须映射为公共数字码，**禁止透传** |
| D43 | 游标分页 | cursor 为不透明 base64 串（含签名），不暴露内部 ID 语义；响应含 `hasMore` / `nextCursor` |
| D44 | 附件（Q6 闭环） | 单文件 10MB；pdf/docx/md/png/jpg；魔数校验；MinIO 预签名 URL；Q6 关闭 |
| D45 | OpenAPI | Java 公共 API 与 Python 内部 API 各自输出 OpenAPI 3；CI 互校 schema 与错误码 |

### 1.4 API 总览

| 类别 | 数量 | 说明 |
|---|---:|---|
| 公共 API（Vue → Java） | 111 | 含 P0/P1/P2，见附录 A |
| Java → Python 内部 API | 9 | 对话、分诊、相似、知识、向量、健康 |
| Python → Java 内部 API | 7 | 工具调用与上下文查询 |
| SSE 流 | 2（MVP 仅 1） | 对话 POST SSE 为 MVP；通知/SLA GET SSE 为 P1 |

---

## 2. API 总体规范

### 2.1 基础地址

| 环境 | 公共 API | Python 内部 API | Java 内部 API |
|---|---|---|---|
| 开发 | `http://127.0.0.1:8200/api/v1` | `http://127.0.0.1:8300/v1` | `http://127.0.0.1:8200/internal/v1` |
| 生产 | `https://{domain}/api/v1` | 内网地址，仅 Java 可达 | 内网地址，Nginx 屏蔽 |

- 前端只访问 Java 公共 API，不直连 Python。
- `/internal/v1/**` 不经 Nginx 暴露；仅 Java 与 Python 内网互通。
- 所有请求默认 `Content-Type: application/json; charset=utf-8`，文件上传为 `multipart/form-data`。

### 2.2 版本与兼容

1. 路径版本：`/api/v1`、`/internal/v1`。
2. 只做向后兼容的增量变更；破坏性变更升 `/v2`。
3. 新增字段默认可选；删除/重命名字段需弃用周期。
4. 枚举新增值需前端容忍未知值（显示原始编码）。
5. 契约以 OpenAPI 为准；本文档描述业务契约与约束。

### 2.3 命名与时区

| 项目 | 约定 |
|---|---|
| 公共 API 字段 | `camelCase` |
| 内部 Java↔Python 字段 | `snake_case`（Java DTO 映射层转换） |
| ID | 统一字符串（雪花 ID 转 String） |
| 时间 | ISO-8601 UTC，如 `2026-11-17T08:30:00.000Z` |
| 日期 | `YYYY-MM-DD` |
| 枚举 | 大写下划线，如 `PENDING_ACCEPT` |
| 空值 | 缺省字段省略；明确语义的 null 才返回 |
| 金额/比例 | 字符串或数值按字段定义；比例 0–1 |

### 2.4 统一响应体

**成功**

```json
{
  "code": 0,
  "message": "ok",
  "data": {},
  "traceId": "019a4f3c2d7e7b4c8a1f0e9d8c7b6a50"
}
```

**失败**

```json
{
  "code": 20002,
  "message": "工单状态已变化，请刷新后重试",
  "data": null,
  "traceId": "019a4f3c2d7e7b4c8a1f0e9d8c7b6a50"
}
```

- HTTP 状态与业务码同时表达语义；前端以 `code` 为准，HTTP 用于网关/监控。
- 错误信息不得包含堆栈、SQL、内部地址或敏感数据。
- 所有响应头返回 `X-Trace-Id`。

### 2.5 分页

**页码分页（列表类）**

| 参数 | 类型 | 默认 | 约束 |
|---|---|---|---|
| `page` | int | 1 | ≥1 |
| `pageSize` | int | 20 | 1–100 |
| `sort` | string | 由接口定义 | 白名单字段，`field,asc/desc` |

响应：

```json
{
  "items": [],
  "total": 0,
  "page": 1,
  "pageSize": 20
}
```

**游标分页（消息/事件/审计类）**

| 参数 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `cursor` | string | 空 | **不透明 base64url 串**；空表示第一页 |
| `limit` | int | 20 | 1–100 |

**cursor 设计**

- 明文结构（服务端可见）：`{v:1, r:<resource>, k:<lastSortKey>, h:<filterHash>}`。
- 对外形式：`base64url(明文) + "." + HMAC-SHA256(明文, 服务端密钥)`；前端不得解析或构造。
- 服务端校验：签名、资源类型、过滤条件哈希；非法/过期返回 `60004`。
- 不暴露内部自增 ID 语义；`lastSortKey` 为服务端排序键（如雪花 ID/时间戳），前端只回传。
- 过滤条件变化后旧 cursor 失效，需从第一页开始。

响应：

```json
{
  "items": [],
  "nextCursor": "eyJ2IjoxLCJyIjoiY2hhdF9tZXNzYWdlIiwiayI6IjEyMzQ1Njc4OTAxMjM0NTY3OCIsImgiOiJhMWIyYzMifQ.c2lnbmF0dXJl",
  "hasMore": true
}
```

- 禁止深分页 `OFFSET`；消息、事件、审计必须使用游标。
- 排序字段必须在白名单内，防止注入与全表排序。
- `hasMore=false` 时 `nextCursor` 为 null。

### 2.6 幂等

| 场景 | Header | 行为 |
|---|---|---|
| 创建工单 | `Idempotency-Key` 必填 | 同 key + 同请求体返回首次结果；不同请求体返回 `20004` |
| 确认分诊建单 | `Idempotency-Key` 必填 | 同 key 幂等；重复确认返回已建工单 |
| 知识入库 | `Idempotency-Key` 可选 | 同 key 不重复创建任务 |
| 工具调用建单 | Python 传 `Idempotency-Key` | 由 `tool_call_id` 派生 |
| 其他写操作 | 可选 | 状态流转靠 `expectedVersion` 防并发 |

- 幂等记录保存 24 小时；Redis 热路径 + `idempotency_record` 持久化。
- 响应头返回 `Idempotent-Replayed: true` 表示命中重放。

### 2.7 追踪与请求标识

| Header | 方向 | 必填 | 说明 |
|---|---|---|---|
| `X-Trace-Id` | 全链路 | 否 | 合法则复用；否则 Java 生成 UUIDv7 |
| `X-Request-Id` | 全链路 | 是（内部） | 单次请求 ID，前端可生成 |
| `X-Trace-Id` 响应头 | 返回 | 是 | 前端错误提示展示 |
| SSE `meta` 事件 | 对话 | 是 | 携带 `traceId`、`sessionId` |

- 格式校验：UUIDv7 或 32 位十六进制；非法值丢弃并重新生成。
- 日志字段：`traceId`、`requestId`、`service`、`route`、`latencyMs`、`errorCode`。

### 2.8 限流

- 统一返回 `429` + `code=50002`。
- 响应头：
  - `X-RateLimit-Limit`
  - `X-RateLimit-Remaining`
  - `X-RateLimit-Reset`
  - `Retry-After`
- 限流维度：用户、IP、接口、SSE 连接数。
- Redis 不可用时使用本地令牌桶兜底；记录告警。

### 2.9 CORS 与内容类型

- 开发允许 `http://127.0.0.1:5180`、`http://localhost:5180`。
- 生产仅允许正式域名。
- 允许方法：`GET, POST, PUT, DELETE, OPTIONS`。
- 允许 Header：`Authorization`、`Content-Type`、`X-Request-Id`、`X-Trace-Id`、`X-CSRF-Token`、`Idempotency-Key`、`Last-Event-ID`。
- 暴露 Header：`X-Trace-Id`、`X-RateLimit-*`、`Idempotent-Replayed`。

---

## 3. 认证与权限设计

### 3.1 JWT 策略（Q3 拍板）

| 项目 | 决策 |
|---|---|
| Access Token | JWT，有效期 **2 小时**；仅存前端内存（Pinia），禁止 `localStorage` / `sessionStorage` |
| Refresh Token | JWT，有效期 **7 天**；`HttpOnly` + `Secure` + `SameSite=Lax` Cookie，`Path=/api/v1/auth` |
| CSRF | Cookie 型端点（refresh/logout）必须携带 `X-CSRF-Token`；服务端校验 Double-Submit + 会话绑定 |
| 校验方式 | **Sa-Token 显式 JWT 无状态模式**；签名校验不依赖 Redis |
| 黑名单 | 可选，Redis key `agent_gongdan:auth:blacklist:{jti}`，TTL = Token 剩余有效期 |
| 刷新轮转 | refresh **一次性使用**；每次刷新签发新 refresh，旧 refresh `jti` 立即进黑名单 |
| 重放检测 | 已进入黑名单的 refresh 再次使用 → 判定 Token 家族泄露，撤销整个家族并强制重新登录 |
| Redis 故障 | access token 仍按无状态有效至过期；**refresh/logout 接口 fail-closed**，返回 `50004`，避免旧 refresh 无法撤销的 7 天风险 |
| 登出 | 清除 refresh Cookie + CSRF Cookie；Redis 可用时写黑名单；失败仍清除 Cookie |
| 前端读取 | 刷新页面用 refresh cookie + CSRF token 换取新 access token；access token 不落持久化存储 |

**Access Token Claims**

| Claim | 说明 |
|---|---|
| `sub` | 用户 ID（字符串） |
| `jti` | Token 唯一 ID |
| `fid` | Token 家族 ID；refresh 重放时撤销整个家族 |
| `tenantId` | 租户 ID（MVP 为 `0`） |
| `roles` | 角色编码数组 |
| `permissions` | 可选；MVP 由 `/me` 返回权限，不放入 Token |
| `iat` / `exp` / `iss` | 签发、过期、签发方 |
| `typ` | `access` / `refresh` |

**CSRF 约定**

- 登录/刷新成功时：服务端设置非 HttpOnly 的 `XSRF-TOKEN` Cookie，并在响应体返回 `csrfToken`。
- 前端在 `POST /auth/refresh`、`POST /auth/logout` 时发送 `X-CSRF-Token: <csrfToken>`。
- 服务端校验：Header 与 Cookie 一致、CSRF token 与当前 refresh 会话绑定、时效性校验。
- access token 走 `Authorization` Header，不依赖 Cookie，因此普通业务 API 不做 CSRF 校验。
- CSRF token 随 refresh 轮转；登出后失效。

**降级说明**：Redis 故障时无法即时吊销 access token，也无法安全撤销旧 refresh；因此 refresh/logout 采用 fail-closed。access token 到期前仍可用，高风险操作（派单、关闭、权限变更）可要求重新认证（P1）。

### 3.2 登录/刷新/登出

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| POST | `/api/v1/auth/login` | 公开 | 账号密码登录 |
| POST | `/api/v1/auth/refresh` | Refresh Cookie + `X-CSRF-Token` | 换取新 access token，并轮换 refresh |
| POST | `/api/v1/auth/logout` | Access Token + `X-CSRF-Token` | 登出并吊销 |
| GET | `/api/v1/auth/me` | Access Token | 当前用户、角色、权限、数据范围 |
| PUT | `/api/v1/auth/password` | Access Token | 修改密码（P1） |

**登录失败策略**：连续失败 5 次锁定 15 分钟；返回 `10004` / `10005`，不泄露账号是否存在。

**Refresh 轮转时序**

1. 客户端携带 refresh Cookie + `X-CSRF-Token` 调用 `/auth/refresh`。
2. 服务端校验签名、类型、`fid`、CSRF、黑名单。
3. 签发新 access + 新 refresh（同 `fid`，新 `jti`），旧 refresh `jti` 写黑名单。
4. 返回新 access token、新 csrfToken，并轮换 Cookie。
5. 若旧 refresh 再次出现 → 撤销该 `fid` 下所有 refresh，强制重新登录。
6. Redis 不可用 → 返回 `50004`，不签发新 refresh；access token 继续有效至过期。

### 3.3 权限模型

- RBAC：用户 → 角色 → 权限点。
- 数据范围：`SELF`（本人）、`DEPT`（本部门）、`ALL`（全部）。
- 权限码格式：`domain:action`。
- 内部调用必须携带 Actor 上下文；Java 内部接口仍做权限与数据范围校验。

**列表接口 dataScope 约定**

| 角色 | 默认 dataScope | 说明 |
|---|---|---|
| `REPORTER` | `SELF` | 仅本人报障/处理/评价的工单 |
| `ENGINEER` | `DEPT` | 本部门工单；跨部门需显式授权 |
| `DISPATCHER` | `ALL` | 派单与全局视图 |
| `KB_ADMIN` | `ALL`（知识域） | 知识管理；业务工单仍按角色 |
| `ADMIN` | `ALL` | 全部 |
| `INSPECTOR` | `SELF` | 本人巡检相关 |

- `DEPT` 过滤依赖 `sys_dept.path` 前缀查询，索引 `idx_sys_dept_path(path(191))`。
- 列表接口必须声明 `dataScope` 取值与默认；详情/写操作按资源归属再次校验。
- 内部查询接口按传入 Actor 的 `dataScope` 过滤，不允许 Python 自行扩大范围。

**核心权限码**

| 权限码 | 说明 |
|---|---|
| `ticket:create` | 创建工单/确认分诊建单 |
| `ticket:read` | 查看工单、时间线、溯源 |
| `ticket:update` | 编辑工单 |
| `ticket:assign` | 派单/转派 |
| `ticket:claim` | 认领 |
| `ticket:handle` | 接单、处理、挂起、恢复、解决 |
| `ticket:verify` | 验证结果 |
| `ticket:close` | 关闭/取消 |
| `ticket:evaluate` | 满意度评价 |
| `chat:use` | 使用 AI 会话 |
| `chat:handoff` | 转人工 |
| `ai:triage` | 调用分诊 |
| `ai:similar` | 相似工单查询 |
| `knowledge:read` | 查看知识 |
| `knowledge:manage` | 知识上传/编辑/启停/重建 |
| `dispatch:manage` | 派单看板/抢单池 |
| `report:view` | 报表 |
| `customer:read` / `customer:manage` | 业户/资源 |
| `equipment:read` / `equipment:manage` | 设备/巡检 |
| `admin:user` / `admin:role` / `admin:config` / `admin:audit` | 系统管理 |

### 3.4 内部服务认证（Q4 拍板）

| 项目 | 决策 |
|---|---|
| 方向拆分 | `Java→Python` 与 `Python→Java` **各一把独立 Token**，互不复用 |
| Header | `X-Internal-Token`；可选 `X-Internal-Token-Kid: current\|next` |
| 来源 | `.env`：`INTERNAL_TOKEN_J2P_CURRENT` / `INTERNAL_TOKEN_J2P_NEXT`、`INTERNAL_TOKEN_P2J_CURRENT` / `INTERNAL_TOKEN_P2J_NEXT`，各 32+ 字节随机值 |
| 比较 | 常量时间比较；不记录到日志；失败返回 `401`，不说明具体原因 |
| 暴露面（dev） | 两端绑定 `127.0.0.1`，仅本机回环可达 |
| 暴露面（prod） | 仅内网/私网；Nginx、Ingress、负载均衡**所有 ingress 一致屏蔽** `/internal/**`；安全组仅允许 Java↔Python 端口互通 |
| 轮换 | current + next 双 Token 并行；轮换确认后旧 Token 失效 |
| Actor 透传 | `X-Actor-Id`、`X-Actor-Roles`、`X-Actor-Dept`；Python 不得自行构造 |

**双 Token 轮换时序**

1. 生成新 Token，写入两侧 `.env` 的 `*_NEXT`，同时保留 `*_CURRENT`。
2. 双方部署为“同时接受 current 与 next”，调用方仍使用 current。
3. 观察一段时间无 401/异常后，调用方切换到 next（`X-Internal-Token-Kid: next`）。
4. 确认全部调用方已切换，且监控无 current 流量。
5. 将 next 提升为 current，删除旧 current 与 next 配置，完成轮换。
6. 任一步失败：保留 current，回滚 next；不得出现两端都只接受单一不匹配 Token 的窗口。

### 3.5 角色与默认权限

| 角色 | 默认权限 |
|---|---|
| `ADMIN` | 全部 |
| `DISPATCHER` | `ticket:*`、`dispatch:manage`、`report:view`、`customer:read`、`ai:similar` |
| `ENGINEER` | `ticket:read`、`ticket:update`、`ticket:claim`、`ticket:handle`、`knowledge:read`、`ai:similar` |
| `REPORTER` | `ticket:create`、`ticket:read`（本人）、`ticket:verify`（本人）、`ticket:evaluate`（本人）、`chat:use` |
| `KB_ADMIN` | `knowledge:*`、`ai:triage`、`report:view` |
| `INSPECTOR` | `ticket:create`（巡检）、`ticket:read`（本人）、`equipment:read` |

---

## 4. 公共 API 清单（Vue → Java）

> 所有公共 API 均需 `Authorization: Bearer <accessToken>`，除登录/刷新/健康检查外。
> 优先级：P0 必做、P1 增强、P2 储备。
> 说明：P0 核心接口在第 8 节给出详细请求/响应契约；P1/P2 接口先给出清单、权限与用途，字段级契约在实现前按第 2 节规范补充。
> 列表接口默认 `dataScope` 见 3.3；`DEPT` 依赖 `sys_dept.path` 前缀索引；详情与写操作按资源归属二次校验。

### 4.1 认证与用户

| 方法 | 路径 | 用途 | 权限 | 优先级 |
|---|---|---|---|---|
| POST | `/api/v1/auth/login` | 登录 | 公开 | P0 |
| POST | `/api/v1/auth/refresh` | 刷新令牌 | Refresh Cookie + CSRF | P0 |
| POST | `/api/v1/auth/logout` | 登出 | 已登录 | P0 |
| GET | `/api/v1/auth/me` | 当前用户与权限 | 已登录 | P0 |
| GET | `/api/v1/users/options` | 派单可选处理人 | `ticket:read` | P0 |
| GET | `/api/v1/profile` | 当前用户个人资料（含审核状态） | 已登录 | P0 |
| PUT | `/api/v1/profile` | 更新联系人/电话/项目/楼宇/房间/客户 | `ticket:create` | P0 |
| GET | `/api/v1/profile/reviews` | 待审核绑定资料 | `admin:user` | P0 |
| POST | `/api/v1/profile/reviews/{userId}` | 通过/驳回绑定审核 | `admin:user` | P0 |
| PUT | `/api/v1/auth/password` | 修改密码 | 已登录 | P1 |
| GET | `/api/v1/users` | 用户列表 | `admin:user` | P1 |
| POST | `/api/v1/users` | 新增用户 | `admin:user` | P1 |
| GET | `/api/v1/users/{id}` | 用户详情 | `admin:user` | P1 |
| PUT | `/api/v1/users/{id}` | 编辑用户 | `admin:user` | P1 |
| PUT | `/api/v1/users/{id}/status` | 启用/禁用 | `admin:user` | P1 |
| PUT | `/api/v1/users/{id}/roles` | 分配角色 | `admin:role` | P1 |
| GET | `/api/v1/roles` | 角色列表 | `admin:role` | P1 |
| GET | `/api/v1/permissions` | 权限点列表 | `admin:role` | P1 |
| GET | `/api/v1/depts` | 部门树 | `admin:user` | P1 |

### 4.2 工单

| 方法 | 路径 | 用途 | 权限 | 优先级 |
|---|---|---|---|---|
| POST | `/api/v1/tickets` | 表单建单 | `ticket:create` | P0 |
| GET | `/api/v1/tickets` | 工单列表 | `ticket:read` | P0 |
| GET | `/api/v1/tickets/export` | Phase A 工单导出（16 列，dataScope 过滤） | `ticket:read` | P0 |
| GET | `/api/v1/tickets/{id}` | 工单详情 | `ticket:read` | P0 |
| GET | `/api/v1/tickets/{id}/similar` | 按工单检索相似历史工单 | `ticket:read` | P0 |
| POST | `/api/v1/tickets/vector/reindex` | 回填工单历史向量 | `admin:config` | P0 |
| POST | `/api/v1/tickets/{id}/finish` | 一键结束工单（置 CLOSED） | `ticket:close` | P0 |
| POST | `/api/v1/tickets/{id}/ai-analysis` | 生成工单 AI 初步分析 | `ticket:read` | P0 |
| GET | `/api/v1/tickets/{id}/ai-analysis` | 最近一次 AI 分析 | `ticket:read` | P0 |
| PUT | `/api/v1/tickets/{id}` | 编辑工单 | `ticket:update` | P0 |
| POST | `/api/v1/tickets/{id}/assign` | 直接派单 | `ticket:assign` | P0 |
| POST | `/api/v1/tickets/{id}/claim` | 认领 | `ticket:claim` | P0 |
| POST | `/api/v1/tickets/{id}/transfer` | 转负责人/转物业部 | `ticket:assign` | P1 |
| POST | `/api/v1/tickets/{id}/accept` | 接单 | `ticket:handle` | P0 |
| POST | `/api/v1/tickets/{id}/resolve` | 提交解决（必填 `serviceItemText`；必须先上传 `ONSITE` 现场照片） | `ticket:handle` | P0 |
| POST | `/api/v1/tickets/{id}/verify` | 验证通过/不通过 | `ticket:verify` | P0 |
| POST | `/api/v1/tickets/{id}/close` | 关闭 | `ticket:close` | P0 |
| POST | `/api/v1/tickets/{id}/reopen` | 重开 | `ticket:close` | P1 |
| POST | `/api/v1/tickets/{id}/cancel` | 取消 | `ticket:close` | P0 |
| POST | `/api/v1/tickets/{id}/hold` | 挂起 | `ticket:handle` | P0 |
| POST | `/api/v1/tickets/{id}/resume` | 恢复 | `ticket:handle` | P0 |
| GET | `/api/v1/tickets/{id}/events` | 时间线（游标） | `ticket:read` | P0 |
| GET | `/api/v1/tickets/{id}/comments` | 评论列表 | `ticket:read` | P0 |
| POST | `/api/v1/tickets/{id}/comments` | 添加评论 | `ticket:read` | P0 |
| POST | `/api/v1/tickets/{id}/attachments?type=USER|ONSITE|GENERAL` | 上传附件（报修人图片用 `USER`，处理人现场照片用 `ONSITE`） | `ticket:update` | P0 |
| DELETE | `/api/v1/tickets/{id}/attachments/{attachmentId}` | 删除附件 | `ticket:update` | P0 |
| GET | `/api/v1/tickets/{id}/materials` | 使用材料列表 | `ticket:read` | P0 |
| POST | `/api/v1/tickets/{id}/materials` | 添加使用材料 | `ticket:handle` | P0 |
| GET | `/api/v1/tickets/{id}/visits` | 回访记录列表 | `ticket:read` | P0 |
| POST | `/api/v1/tickets/{id}/visits` | 登记回访 | `ticket:close` | P0 |
| POST | `/api/v1/tickets/{id}/satisfaction` | 满意度评价 | `ticket:evaluate` | P1 |
| GET | `/api/v1/tickets/{id}/trace` | 建单溯源 | `ticket:read` | P0 |

### 4.3 AI 会话

| 方法 | 路径 | 用途 | 权限 | 优先级 |
|---|---|---|---|---|
| POST | `/api/v1/chat/sessions` | 创建会话 | `chat:use` | P0 |
| GET | `/api/v1/chat/sessions` | 我的会话列表 | `chat:use` | P0 |
| GET | `/api/v1/chat/sessions/{id}` | 会话详情 | `chat:use` | P0 |
| DELETE | `/api/v1/chat/sessions/{id}` | 关闭/归档会话 | `chat:use` | P0 |
| GET | `/api/v1/chat/sessions/{id}/messages` | 消息游标分页 | `chat:use` | P0 |
| POST | `/api/v1/chat/sessions/{id}/messages` | 发送消息（SSE） | `chat:use` | P0 |
| POST | `/api/v1/chat/sessions/{id}/close` | 结束会话 | `chat:use` | P0 |
| POST | `/api/v1/chat/sessions/{id}/handoff` | 转人工 | `chat:handoff` | P1 |
| GET | `/api/v1/chat/sessions/{id}/tickets` | 会话关联工单 | `chat:use` | P0 |
| GET | `/api/v1/chat/sessions/{id}/triage-records` | 会话分诊记录 | `chat:use` | P0 |
| POST | `/api/v1/chat/triage-records/{id}/confirm` | 确认分诊草稿并建单 | `chat:use` + `ticket:create` | P0 |
| POST | `/api/v1/chat/tool-calls/{id}/confirm` | 确认高风险工具（`ticket_create`）并建单 | `chat:use` + `ticket:create` | P0 |
| POST | `/api/v1/chat/export-calls/{id}/confirm` | 确认 AI 导出草稿并生成 Excel | `chat:use` + `ticket:read` | P0 |
| POST | `/api/v1/chat/messages/{messageId}/feedback` | 消息反馈 | `chat:use` | P2 |

### 4.4 分诊、相似工单与溯源

| 方法 | 路径 | 用途 | 权限 | 优先级 |
|---|---|---|---|---|
| POST | `/api/v1/ai/triage` | 意图识别与分诊 | `ai:triage` | P0 |
| GET | `/api/v1/ai/triage/{triageId}` | 分诊记录详情 | `ai:triage` | P0 |
| POST | `/api/v1/ai/triage/{triageId}/confirm` | 确认草稿建单 | `ticket:create` | P0 |
| POST | `/api/v1/ai/triage/{triageId}/cancel` | 取消草稿 | `ai:triage` | P0 |
| POST | `/api/v1/ai/similar-tickets` | 相似工单检索 | `ai:similar` | P0 |

### 4.5 知识库

| 方法 | 路径 | 用途 | 权限 | 优先级 |
|---|---|---|---|---|
| POST | `/api/v1/knowledge/docs` | 上传知识文档 | `knowledge:manage` | P0 |
| GET | `/api/v1/knowledge/docs` | 文档列表 | `knowledge:read` | P0 |
| GET | `/api/v1/knowledge/docs/{id}` | 文档详情 | `knowledge:read` | P0 |
| GET | `/api/v1/knowledge/docs/{id}/download` | 下载知识原始文件（预签名） | `knowledge:read` | P0 |
| PUT | `/api/v1/knowledge/docs/{id}` | 编辑元数据 | `knowledge:manage` | P0 |
| DELETE | `/api/v1/knowledge/docs/{id}` | 删除/停用 | `knowledge:manage` | P0 |
| POST | `/api/v1/knowledge/docs/{id}/reindex` | 重建索引 | `knowledge:manage` | P0 |
| PUT | `/api/v1/knowledge/docs/{id}/visibility` | 设置可见范围 | `knowledge:manage` | P1 |
| GET | `/api/v1/knowledge/docs/{id}/chunks` | 分块列表 | `knowledge:read` | P0 |
| GET | `/api/v1/knowledge/jobs/{jobNo}` | 入库/索引任务状态 | `knowledge:read` | P0 |
| POST | `/api/v1/knowledge/search` | 知识检索测试 | `knowledge:read` | P0 |

### 4.6 派单与通知

| 方法 | 路径 | 用途 | 权限 | 优先级 |
|---|---|---|---|---|
| GET | `/api/v1/dispatch/board` | 派单看板 | `dispatch:manage` | P1 |
| GET | `/api/v1/dispatch/claim-pool` | 抢单池 | `dispatch:manage` | P1 |
| POST | `/api/v1/dispatch/claim-pool/{ticketId}/claim` | 抢单 | `ticket:claim` | P1 |
| POST | `/api/v1/dispatch/claim-pool/{ticketId}/release` | 释放回池 | `dispatch:manage` | P1 |
| GET | `/api/v1/notifications` | 通知列表 | 已登录 | P1 |
| PUT | `/api/v1/notifications/{id}/read` | 标记已读 | 已登录 | P1 |
| PUT | `/api/v1/notifications/read-all` | 全部已读 | 已登录 | P1 |
| GET | `/api/v1/notifications/stream` | 通知/SLA 预警 SSE | 已登录 | P1 |
| GET | `/api/v1/notifications/preferences` | 通知偏好 | 已登录 | P2 |
| PUT | `/api/v1/notifications/preferences` | 更新偏好 | 已登录 | P2 |

### 4.7 业户、资源与设备

| 方法 | 路径 | 用途 | 权限 | 优先级 |
|---|---|---|---|---|
| GET | `/api/v1/master-data/options` | 建单主数据选项（项目/楼宇/房间/客户/设备/服务项） | `ticket:read` | P0 |
| GET | `/api/v1/customers` | 业户列表 | `customer:read` | P1 |
| POST | `/api/v1/customers` | 新增业户 | `customer:manage` | P1 |
| GET | `/api/v1/customers/{id}` | 业户详情 | `customer:read` | P1 |
| PUT | `/api/v1/customers/{id}` | 编辑业户 | `customer:manage` | P1 |
| DELETE | `/api/v1/customers/{id}` | 停用业户 | `customer:manage` | P1 |
| GET | `/api/v1/resources/buildings` | 楼栋列表 | `customer:read` | P1 |
| GET | `/api/v1/resources/rooms` | 房号列表 | `customer:read` | P1 |
| POST | `/api/v1/resources/rooms` | 新增房号 | `customer:manage` | P1 |
| GET | `/api/v1/resources/map` | 资源图数据 | `customer:read` | P1 |
| GET | `/api/v1/resources/tenancies` | 租住关系 | `customer:read` | P1 |
| POST | `/api/v1/resources/tenancies` | 新增租住关系 | `customer:manage` | P1 |
| GET | `/api/v1/equipment-categories` | 设备分类 | `equipment:read` | P1 |
| POST | `/api/v1/equipment-categories` | 新增分类 | `equipment:manage` | P1 |
| GET | `/api/v1/equipment` | 设备列表 | `equipment:read` | P1 |
| POST | `/api/v1/equipment` | 新增设备 | `equipment:manage` | P1 |
| GET | `/api/v1/equipment/{id}` | 设备详情 | `equipment:read` | P1 |
| PUT | `/api/v1/equipment/{id}` | 编辑设备 | `equipment:manage` | P1 |
| DELETE | `/api/v1/equipment/{id}` | 停用设备 | `equipment:manage` | P1 |
| GET | `/api/v1/equipment/{id}/history` | 设备维修/巡检历史 | `equipment:read` | P2 |
| GET | `/api/v1/inspection/plans` | 巡检计划 | `equipment:read` | P2 |
| POST | `/api/v1/inspection/plans` | 新增计划 | `equipment:manage` | P2 |
| GET | `/api/v1/inspection/tasks` | 巡检任务 | `equipment:read` | P2 |
| POST | `/api/v1/inspection/tasks/{id}/execute` | 执行巡检 | `equipment:manage` | P2 |

### 4.8 报表与系统管理

| 方法 | 路径 | 用途 | 权限 | 优先级 |
|---|---|---|---|---|
| GET | `/api/v1/reports/tickets/overview` | 工单总览 | `report:view` | P1 |
| GET | `/api/v1/reports/tickets/trend` | 工单趋势 | `report:view` | P1 |
| GET | `/api/v1/reports/sla` | SLA 达成 | `report:view` | P1 |
| GET | `/api/v1/reports/satisfaction` | 满意度 | `report:view` | P1 |
| GET | `/api/v1/reports/ai` | AI 效果指标 | `report:view` | P2 |
| GET | `/api/v1/admin/dicts/{typeCode}` | 字典查询 | `admin:config` | P1 |
| POST | `/api/v1/admin/dicts/{typeCode}/items` | 新增字典项 | `admin:config` | P1 |
| PUT | `/api/v1/admin/dicts/items/{id}` | 编辑字典项 | `admin:config` | P1 |
| DELETE | `/api/v1/admin/dicts/items/{id}` | 删除字典项 | `admin:config` | P1 |
| GET | `/api/v1/admin/configs` | 业务配置查询 | `admin:config` | P1 |
| PUT | `/api/v1/admin/configs/{key}` | 更新业务配置 | `admin:config` | P1 |
| GET | `/api/v1/admin/audit-logs` | 审计日志 | `admin:audit` | P1 |
| GET | `/api/v1/admin/ai-configs` | AI 参数查询 | `admin:config` | P2 |
| PUT | `/api/v1/admin/ai-configs` | AI 参数更新 | `admin:config` | P2 |

### 4.9 健康检查

| 方法 | 路径 | 用途 | 鉴权 |
|---|---|---|---|
| GET | `/api/v1/health` | Java 存活 | 公开/内网 |
| GET | `/api/v1/health/ai` | AI 熔断器状态（`UP`/`DEGRADED`） | 公开/内网 |
| GET | `/api/v1/ready` | Java 就绪 | 内网 |

### 4.10 Phase 1 实现状态对账（2026-11-18）

> 本节只记录“设计清单 vs 当前代码”的差异，不改变第 4 节契约。P1/P2 仍按原优先级保留。

| 项 | 数量 / 说明 |
|---|---|
| 本文档设计接口 | 约 251 条（含 P1/P2 规划） |
| Java 已实现映射 | 64 个注解（含内部接口与健康检查；一个映射可含多个路径） |
| Python 已实现路由 | 10 个（对话、分诊、分析、知识、向量、健康） |
| 本次补录 | `GET /api/v1/users/options`、`GET /api/v1/health/ai`、`GET /internal/v1/tickets/similar`、`POST /internal/v1/knowledge/search` |
| 设计已列但未实现 | P1/P2 接口（报表、系统管理、业户/设备、通知 SSE、抢单池等），前端暂无入口 |
| 已知契约偏差 | 当前未实现 refresh/logout/CSRF（access 为本地持久化）；内部接口按实际实现收敛，不改变公共契约 |

**维护规则**：新增/修改 Controller 或 Python 路由时，同一提交内更新本节数量与差异；P0 接口变更必须先改本文档再改代码。

### 4.11 子 Agent 接口（`/agent/v1`）

> 用于未来大项目/Orchestrator 调用当前平台；鉴权复用内部令牌，Actor 通过 `X-Actor-Id` / `X-Actor-Roles` 或短时 `X-Delegation-Token` 传递。

| 方法 | 路径 | 用途 | 能力/权限 |
|---|---|---|---|
| GET | `/agent/v1/manifest` | Agent Card/能力清单 | 内部令牌 |
| GET | `/agent/v1/capabilities` | 能力清单别名 | 内部令牌 |
| GET | `/agent/v1/card` | Agent Card | 内部令牌 |
| GET | `/.well-known/agent-card.json`、`/agent-card.json` | A2A 风格发现端点 | 公开（不含密钥） |
| POST | `/agent/v1/tasks` | 提交只读/写任务 | 见 Manifest 能力清单 |
| GET | `/agent/v1/tasks/{taskId}` | 查询任务结果 | 内部令牌 + 同 Actor |
| GET | `/agent/v1/tasks/{taskId}/events` | 任务生命周期事件（历史） | 内部令牌 + 同 Actor |
| GET | `/agent/v1/tasks/{taskId}/events/stream` | SSE 任务事件流（回放 + 增量 + done） | 内部令牌 + 同 Actor |
| POST | `/agent/v1/tasks/{taskId}/confirm` | 确认写任务 | 同 Actor + `confirmationToken` |
| POST | `/agent/v1/tasks/{taskId}/cancel` | 取消任务 | 同 Actor |
| POST | `/agent/v1/tickets/{ticketId}/attachments?type=ONSITE\|USER\|GENERAL` | 子 Agent 上传附件/现场照片 | 内部令牌；ONSITE 需处理人；委托需 scope |
| POST | `/agent/v1/delegation/token` | 签发短时委托令牌 | 内部令牌；令牌不能再次委托 |
| GET | `/agent/v1/metrics/summary` | 任务/对话/工具调用聚合指标 | 内部令牌 + ADMIN |

P4 已实现：`SYNC/ASYNC` 任务、`PENDING→RUNNING→SUCCEEDED/FAILED`、进程内队列端口、SSE 事件流、HMAC 委托令牌与 scope、独立 `agent` Profile（只暴露 `/agent/**`）。仍未做：持久化 MQ、进程崩溃任务恢复、真实 token/成本、完整 A2A 注册中心、物理拆服务。

---

## 5. Java → Python 内部 API

> 所有请求头必须包含：`X-Internal-Token`（**方向为 Java→Python，使用 `INTERNAL_TOKEN_J2P_*`**）、`X-Trace-Id`、`X-Request-Id`、`X-Actor-Id`、`X-Actor-Roles`；可选 `X-Actor-Dept`、`X-Internal-Token-Kid`。
> 字段使用 `snake_case`。SSE 请求 `Accept: text/event-stream`；Python 上游帧为 `snake_case`，由 Java 适配层转为 `camelCase` 后下发 Vue。

| 方法 | 路径 | 用途 | 超时 | 幂等 |
|---|---|---|---|---|
| POST | `/v1/agent/chat` | 对话与 Agent 编排（SSE） | 连接 1s / 读 30s / 空闲 60s | 首字节后不重试 |
| POST | `/v1/ai/triage` | 同步分诊 | 10s | 可重试 1 次 |
| POST | `/v1/ai/similar/tickets` | 相似工单检索 | 8s | 可重试 1 次 |
| POST | `/v1/knowledge/ingest` | 文档解析/分块/向量化（异步） | 5s（返回 202） | 可重试 1 次 |
| POST | `/v1/knowledge/search` | 知识检索 | 5s | 可重试 2 次 |
| POST | `/v1/vector/index` | 工单/预分块内容向量化 | 5s（返回 202） | 可重试 1 次 |
| GET | `/v1/vector/jobs/{jobNo}` | 向量任务状态 | 3s | 可重试 2 次 |
| GET | `/v1/health` | Python 存活 | 1s | 不重试 |
| GET | `/v1/ready` | Python 就绪 | 1s | 不重试 |

**关键约定**

1. `/v1/agent/chat` 返回 SSE 事件流，事件协议见第 7 节。
2. Python 不写 MySQL；分诊/工具调用结果由 Java 落库。
3. 知识入库与向量索引为异步任务：Java 创建 `vector_index_job`，调用 Python，轮询 `/v1/vector/jobs/{jobNo}` 并更新状态。
4. `object_type` 仅允许 `KB_CHUNK` / `TICKET`；collection 统一命名 `agent_gongdan_{type}_{model}_{dim}_v{version}`，知识为 `agent_gongdan_kb_bge_m3_1024_v1`，工单为 `agent_gongdan_ticket_bge_m3_1024_v1`，由 `sys_config` 驱动并启动校验。
5. 相似工单返回 `ticket_id` 与 `score` 列表，Java 批量查询工单并组装（禁止 JOIN）。
6. 工单向量触发：建单写标题+描述向量，完结补充解决方案向量；dev 引导任务为演示工单生成初始向量。
7. `/v1/agent/chat` 请求体（`snake_case`）：`trace_id`、`session_id`、`assistant_message_id`、`model`、`messages[]`（历史轮次，含本轮用户消息）、`context_chunks[]`（`doc_id`、`chunk_id`、`title`、`content`、`score`、`rank`）。MVP 由 Java 完成知识召回、分块内容补全与历史读取，Python 仅组装提示词并流式生成，**保持无状态、不访问 MySQL**；后续若引入独立上下文服务，只替换 `context_chunks` 的来源，不改公共 API。
8. Python 上游帧固定 `snake_case`；Java 适配层逐帧转换为 `camelCase` 后下发，禁止整段缓冲或透传内部事件名。

---

## 6. Python → Java 内部 API

> 仅内网可达；必须携带 `X-Internal-Token`（**方向为 Python→Java，使用 `INTERNAL_TOKEN_P2J_*`**）、`X-Trace-Id`、`X-Request-Id`、`X-Actor-*`、`Idempotency-Key`（建单必填）。
> Nginx、Ingress、负载均衡等**所有 ingress 一致屏蔽** `/internal/**`；开发环境两端绑定回环，生产仅私网互通。
> Java 内部接口按传入 Actor 的 `dataScope` 过滤，不因“内部调用”跳过权限；Python 不得自行扩大范围。
> 会话与工单的关联一律写 `chat_session_ticket`；`chat_session.ticket_id` 仅表示最近主工单，不作为关联依据。

| 方法 | 路径 | 用途 | 超时 | 幂等 |
|---|---|---|---|---|
| POST | `/internal/v1/tickets` | 工具建单 | 5s | 是（`Idempotency-Key`） |
| GET | `/internal/v1/tickets/{idOrNo}` | 查询工单（支持数字 ID 或工单号） | 3s | 是 |
| GET | `/internal/v1/tickets` | 我的/待办工单 | 3s | 是 |
| GET | `/internal/v1/tickets/similar` | 相似历史工单检索 | 5s | 是 |
| POST | `/internal/v1/tickets/export` | 生成工单 Excel 到 MinIO，返回预签名 URL | 10s | 是 |
| POST | `/internal/v1/knowledge/search` | 知识检索（Agent 工具） | 5s | 是 |
| POST | `/internal/v1/tickets/{id}/assign` | 派单（P1） | 3s | 否 |
| POST | `/internal/v1/chat/sessions/{id}/handoff` | 转人工 | 3s | 是（session+turn） |
| GET | `/internal/v1/context` | 业务上下文 | 3s | 是 |
| GET | `/internal/v1/knowledge/documents/{id}/content` | 文档内容/预签名 | 5s | 是 |

**内部请求示例字段**

- 建单：`title`、`description`、`ticket_type_code`、`priority_code`、`customer_id`、`room_id`、`equipment_id`、`source_ref_type`、`source_ref_id`、`triage_id`、`idempotency_key`。
- 建单成功后由 Java 写 `chat_session_ticket`（`relation_type=CREATED`、`created_tool_call_id`），并回填 `ticket.triage_id`；**不依赖 `chat_session.ticket_id` 做关联**。
- 上下文：`actor`（含 `data_scope`）、`customer_id`、`room_id`、`equipment_id`、`include_history`。
- 所有响应返回 `trace_id`；错误使用内部 snake_case code，由 Java 映射为公共数字码后返回前端（禁止透传）。

---

## 7. SSE 事件协议

### 7.1 对话流

**请求**

```http
POST /api/v1/chat/sessions/{sessionId}/messages
Authorization: Bearer <accessToken>
Accept: text/event-stream
Content-Type: application/json
X-Request-Id: <uuid>
```

```json
{
  "clientMessageId": "c-7f3a2b",
  "content": "A 栋 2 号梯有异响，麻烦报修",
  "contentType": "TEXT",
  "attachmentFileIds": [],
  "context": {
    "customerId": "10001",
    "roomId": "20001",
    "equipmentId": "30002"
  }
}
```

**事件序列**

| event | data 关键字段 | 说明 |
|---|---|---|
| `meta` | `traceId`、`sessionId`、`assistantMessageId`、`model`、`degraded` | 首帧 |
| `delta` | `content` | 增量文本 |
| `citation` | `docId`、`chunkId`、`title`、`score`、`rank` | 知识引用 |
| `triage` | `triageId`、`intent`、`confidence`、`action`、`lowConfidence` | 分诊结果（已回填 02） |
| `tool_call` | `id`、`toolCallId`、`name`、`status`、`arguments`、`argumentsSummary`、`requiresConfirm`、`resultSummary`、`resultTicketId`、`errorCode` | 工具调用；`status=PENDING` 表示等待用户确认 |
| `export_card` | `toolCallId`、`summary`、`filters`、`requiresConfirm` | AI 导出确认卡；`ticket_export` 草稿专用 |
| `ticket_card` | `state`（`DRAFT`/`CREATED`）、`ticketId`、`ticketNo`、`statusCode`、`title`、`draft`、`requiresConfirm` | 草稿与已建单统一事件 |
| `done` | `finishReason`、`usage`、`messageId`、`latencyMs` | 正常结束 |
| `error` | `code`、`message`、`retryable`、`degraded` | 错误结束 |
| `ping` | `ts` | 心跳（已回填 02） |

**规则**

1. 首帧必须 `meta`；结束必须 `done` 或 `error`。
2. 每帧带 `id`（递增整数）；对话 SSE **不支持 `Last-Event-ID` 续传**；断线由用户重新发起，使用相同 `clientMessageId` 保证幂等。
3. Java 逐帧转发，不缓冲整段；客户端断开时取消上游订阅。
4. `ping` 间隔 **15–20s**，必须小于 60s 空闲超时；空闲超过 60s 发送 `error`（`30007`）并关闭。
5. 流结束后 Java 一次性落库助手消息、引用、工具调用；不逐 token 写库。
6. 失败时助手消息置 `FAILED`，保留部分内容；用户可用同一个 `clientMessageId` 重试。
7. Python 上游帧使用 `snake_case`；Java 适配层转换为 `camelCase` 后下发 Vue。字段映射错误视为契约缺陷。
8. 事件全集与 02 §7.2 对齐：`ticket_card` 为唯一建单事件（`state=DRAFT|CREATED`）；不再使用 `ticket_draft`；新增事件只做增量。
9. 草稿不自动建单：用户携带 `triageId` 调用 `POST /api/v1/chat/triage-records/{id}/confirm` 后才写 `ticket`、`chat_session_ticket` 与 `ticket.triage_id`；同一 `triageId` 重复确认返回同一工单（幂等键 `triage:{id}`）。
10. 高风险工具不自动执行：`ticket_create` 仅产生 `tool_call(status=PENDING, requiresConfirm=true)` 与草稿参数；用户调用 `POST /api/v1/chat/tool-calls/{id}/confirm` 后由 Java 建单（`source_code=AGENT`），幂等键 `tool:{toolCallId}`，并写 `chat_session_ticket.created_tool_call_id`。
11. 同一助手消息可能同时产生分诊草稿与 `ticket_create` 工具草稿；任一路径确认后，另一路径置 `CANCELLED`，避免重复建单。
12. `ticket_export` 只产生 `tool_call(status=PENDING)` 与 `export_card`，不生成文件；用户调用 `POST /api/v1/chat/export-calls/{id}/confirm` 后，Java 生成 Excel、写入 MinIO `exports/` 并返回 10 分钟预签名 URL。

**示例**

```text
event: meta
data: {"traceId":"019a...","sessionId":"SESS0002","assistantMessageId":"MSG0010","model":"deepseek-chat","degraded":false}

event: triage
data: {"triageId":"TRG0002","intent":"FAULT_REPORT","confidence":0.88,"action":"CREATE_DRAFT","lowConfidence":false}

event: citation
data: {"docId":"KB0001","chunkId":"CH0003","title":"电梯困人应急处置手册","score":0.82,"rank":1}

event: ticket_card
data: {"state":"DRAFT","triageId":"TRG0002","requiresConfirm":true,"draft":{"title":"A 栋 2 号梯运行异响","ticketTypeCode":"ELEVATOR","priorityCode":"NORMAL","locationText":"A 栋"}}

event: done
data: {"finishReason":"stop","usage":{"inputTokens":820,"outputTokens":260},"messageId":"MSG0010","latencyMs":2140}
```

### 7.2 通知 / SLA 预警流（P1，不进 MVP 详细契约）

> MVP **不实现**通知/SLA GET SSE，仅保留对话 POST SSE。以下为 P1 设计保留，字段级契约在 P1 阶段细化。

**请求（P1）**

```http
GET /api/v1/notifications/stream
Authorization: Bearer <accessToken>
Accept: text/event-stream
```

**事件（P1）**

| event | data 关键字段 | 说明 |
|---|---|---|
| `connected` | `userId`、`traceId` | 连接建立 |
| `notification` | `notificationId`、`typeCode`、`title`、`bizType`、`bizId` | 普通通知 |
| `sla_warning` | `ticketId`、`ticketNo`、`level`、`effectiveDueAt`、`remainingMinutes`、`assigneeId` | SLA 预警/超时 |
| `ticket_update` | `ticketId`、`statusCode`、`updatedAt` | 工单状态变化 |
| `heartbeat` | `ts` | 心跳（20s） |

**SLA 扫描与挂起扣除（P1）**

- 基础到期：`base_due_at = created_at + resolve_minutes`。
- 挂起累计：`paused_minutes = Σ(已结束 hold 区间) + (now - hold_started_at，若当前挂起)`。
- 有效到期：`effective_due_at = base_due_at + paused_minutes`；等价于“从 SLA 计时中扣除挂起时长”。
- 扫描条件：`effective_due_at < now` 且工单未终结 → 写 `SLA_BREACHED` 事件并置 `sla_resolve_breached=1`。
- 预警阈值：剩余时间 ≤ 20%（`sla.warning.ratio=0.2`）→ `WARNING`；到期 → `BREACHED`。
- 同一工单同一级别 30 分钟内不重复推送（Redis 去重，前缀 `agent_gongdan:sla:push:`）。
- 生成 `sys_notification` 记录；离线用户下次登录通过通知列表补齐。

**断线重连（P1）**

- 支持 `Last-Event-ID`（通知 ID）从 `sys_notification` 回放最近 50 条。
- MVP（未实现 SSE）使用通知列表轮询；P1 实现 SSE 后，SSE 不可用时回退每 30s 轮询。

---

## 8. 关键接口详细契约

### 8.1 登录 / 刷新 / 登出

**POST `/api/v1/auth/login`**

```json
{
  "username": "engineer01",
  "password": "********"
}
```

成功响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "accessToken": "<jwt>",
    "expiresIn": 7200,
    "tokenType": "Bearer",
    "csrfToken": "<csrf>",
    "user": {
      "id": "10001",
      "username": "engineer01",
      "displayName": "张工",
      "roles": ["ENGINEER"],
      "permissions": ["ticket:read", "ticket:handle", "chat:use"]
    }
  },
  "traceId": "019a..."
}
```

- `refreshToken` 通过 `Set-Cookie` 返回：`HttpOnly; Secure; SameSite=Lax; Path=/api/v1/auth`。
- 服务端同时设置非 HttpOnly 的 `XSRF-TOKEN` Cookie；前端发送 `X-CSRF-Token` Header。
- access token 仅存内存（Pinia），禁止 `localStorage` / `sessionStorage`。
- 失败返回 `10004`；锁定返回 `10005`；不区分账号不存在与密码错误。

**POST `/api/v1/auth/refresh`**

- 无 JSON body；依赖 refresh Cookie + `X-CSRF-Token`。
- 成功返回新的 `accessToken`、新 `csrfToken`，并轮换 refresh Cookie。
- 旧 refresh 立即进黑名单；重复使用旧 refresh → 撤销整个 `fid` 家族。
- Redis 不可用 → 返回 `50004`（fail-closed），前端提示稍后重试或重新登录；access token 仍有效至过期。
- 失败 `10006`，前端跳转登录页。

**POST `/api/v1/auth/logout`**

- 需要 access token + `X-CSRF-Token`。
- 清除 refresh/CSRF Cookie；Redis 可用时写黑名单。
- 返回 `204` 或统一成功响应。

### 8.2 建单（表单 / 分诊确认）

**POST `/api/v1/tickets`**

Header：`Idempotency-Key: <uuid>`

```json
{
  "title": "A 栋 2 号梯运行异响",
  "description": "电梯运行时有金属摩擦声，已停梯",
  "ticketTypeCode": "ELEVATOR",
  "priorityCode": "NORMAL",
  "sourceCode": "CHAT",
  "sourceRefType": "CHAT_SESSION",
  "sourceRefId": "SESS0002",
  "triageId": "TRG0002",
  "customerId": "10001",
  "roomId": "20001",
  "equipmentId": "30002",
  "locationText": "A 栋 2 单元",
  "attachmentFileIds": []
}
```

成功响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "ticketId": "50001",
    "ticketNo": "GD202611170002",
    "statusCode": "PENDING",
    "version": 0,
    "slaResponseDueAt": "2026-11-17T12:00:00.000Z",
    "slaResolveDueAt": "2026-11-18T08:00:00.000Z"
  },
  "traceId": "019a..."
}
```

- 必填：`title`、`ticketTypeCode`、`reporter` 由 Token 推断；`priorityCode` 默认 `NORMAL`。
- `sourceRefType` 与 `sourceRefId` 必须成对出现；`triageId` 可选。
- 同一 `Idempotency-Key` 重放返回首次结果，并带 `Idempotent-Replayed: true`；**幂等重放不是错误**（HTTP 200）。
- 幂等键贯穿：`idempotency_record(scope=TICKET_CREATE)` 保存响应；`triage_record.draft_status=CONFIRMED` + `confirmed_at`；`ticket.triage_id` 回指。确认分诊接口使用同一 `Idempotency-Key`，避免“分诊已确认、工单未创建”或重复建单。
- 权限：`ticket:create`；数据范围校验。

### 8.3 工单列表与详情

**GET `/api/v1/tickets`**

| 参数 | 类型 | 说明 |
|---|---|---|
| `statusCodes` | string[] | 状态多选 |
| `priorityCodes` | string[] | 优先级多选 |
| `ticketTypeCodes` | string[] | 类型多选 |
| `assigneeId` | string | 处理人 |
| `reporterId` | string | 报障人 |
| `customerId` | string | 业户 |
| `roomId` | string | 房号 |
| `equipmentId` | string | 设备 |
| `keyword` | string | 标题/描述关键词（P1 全文检索） |
| `createdFrom` / `createdTo` | string | ISO-8601 |
| `page` / `pageSize` | int | 分页 |

响应条目：

```json
{
  "id": "50001",
  "ticketNo": "GD202611170002",
  "title": "A 栋 2 号梯运行异响",
  "statusCode": "PROCESSING",
  "priorityCode": "NORMAL",
  "ticketTypeCode": "ELEVATOR",
  "reporterId": "10002",
  "assigneeId": "10003",
  "customerId": "10001",
  "roomId": "20001",
  "equipmentId": "30002",
  "createdAt": "2026-11-17T08:00:00.000Z",
  "updatedAt": "2026-11-17T08:30:00.000Z",
  "slaResolveDueAt": "2026-11-18T08:00:00.000Z",
  "slaResolveBreached": false
}
```

**GET `/api/v1/tickets/{id}`**

- 返回主表字段、SLA 快照、关联业户/房号/设备摘要、最近事件、附件、评论数、工具/分诊溯源入口。
- 关联数据由 Java 批量查询后组装，不使用 JOIN。
- 数据权限：`SELF` 仅本人报障/处理；`DEPT` 本部门；`ALL` 全部。

### 8.4 状态流转接口

统一请求字段：

```json
{
  "expectedVersion": 3,
  "reason": "已更换轴承",
  "assigneeId": "10003",
  "deptId": "200",
  "resolution": "更换轴承并试运行",
  "solution": "更换 2 号梯轴承，运行正常"
}
```

| 接口 | 必填字段 | 说明 |
|---|---|---|
| `POST /assign` | `expectedVersion`、`assigneeId` | 直接派单 |
| `POST /claim` | `expectedVersion` | 工程师认领 |
| `POST /transfer` | `expectedVersion`、`toAssigneeId` / `toDeptId`、`reason` | 转负责人/转物业部 |
| `POST /accept` | `expectedVersion` | 接单 |
| `POST /resolve` | `expectedVersion`、`resolution`、`solution` | 提交解决 |
| `POST /verify` | `expectedVersion`、`approved`、`reason` | 验证通过/不通过 |
| `POST /close` | `expectedVersion`、`closeReasonCode` | 关闭 |
| `POST /reopen` | `expectedVersion`、`reason` | 重开 |
| `POST /cancel` | `expectedVersion`、`reason` | 取消 |
| `POST /hold` | `expectedVersion`、`reason` | 挂起 |
| `POST /resume` | `expectedVersion` | 恢复，按挂起时长顺延 SLA |

统一响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "ticketId": "50001",
    "statusCode": "PROCESSING",
    "version": 4,
    "eventId": "60001",
    "slaResolveDueAt": "2026-11-18T10:00:00.000Z"
  },
  "traceId": "019a..."
}
```

- `expectedVersion` 与当前 `version` 不一致 → `20002`（409，乐观锁冲突）。
- 非法状态流转 → `20002`；抢单冲突 → `20003`。
- 挂起/恢复写 `ticket_hold`；`pause_on_hold=1` 时按挂起时长顺延 SLA。
- **SLA 有效到期公式**：`effective_due_at = base_due_at + Σ(hold_minutes)`；当前仍在挂起时，`Σ` 包含 `now - hold_started_at`。扫描以 `effective_due_at` 为准，等价于从 SLA 计时中扣除挂起时长。
- 重复提交同一状态变更（相同 `expectedVersion`）返回 `20002`；幂等重放仅适用于带 `Idempotency-Key` 的建单/确认接口，返回 HTTP 200 且 `Idempotent-Replayed: true`。
- 转物业部使用 `TRANSFER_DEPT`，目标状态 `PENDING_ACCEPT`。

### 8.5 分诊与确认建单

**POST `/api/v1/ai/triage`**

```json
{
  "sessionId": "SESS0002",
  "messageId": "MSG0009",
  "text": "A 栋 2 号梯有异响，麻烦报修",
  "context": {
    "customerId": "10001",
    "roomId": "20001",
    "equipmentId": "30002"
  },
  "options": {
    "includeDraft": true
  }
}
```

成功响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "triageId": "TRG0002",
    "intent": "FAULT_REPORT",
    "entities": {
      "equipmentId": "30002",
      "location": "A 栋 2 单元",
      "symptom": "异响"
    },
    "confidence": 0.88,
    "threshold": 0.75,
    "action": "CREATE_DRAFT",
    "lowConfidence": false,
    "draft": {
      "title": "A 栋 2 号梯运行异响",
      "ticketTypeCode": "ELEVATOR",
      "priorityCode": "NORMAL",
      "description": "电梯运行时有金属摩擦声，已停梯"
    },
    "citations": [],
    "degraded": false
  },
  "traceId": "019a..."
}
```

- `action`：`ANSWER` / `CREATE_DRAFT` / `CLARIFY` / `HANDOFF`。
- `lowConfidence=true` 时返回 `clarifyQuestion`，不自动建单。
- **分诊记录由 Java 落库**：Python 只返回分诊结果；Java 写 `triage_record`（`intent`、`entities`、`confidence`、`threshold`、`action`、`draft`、`model`、`trace_id`）。
- 建单时 Java 写 `ticket.triage_id` 回指；`triage_record.draft_status` 与 `confirmed_at` 同步更新。
- AI 不可用时返回 `30001` 或降级为规则分诊，`degraded=true`。

**POST `/api/v1/ai/triage/{triageId}/confirm`**

Header：`Idempotency-Key: <uuid>`（与建单接口使用同一语义的键）

```json
{
  "draftPatch": {
    "priorityCode": "URGENT"
  }
}
```

- 仅允许 `draft_status=DRAFT` 的确认；重复确认返回已建工单。
- 创建 `ticket`、`ticket_event`、`chat_session_ticket`（如来自会话），并写审计；**会话关联一律走 `chat_session_ticket`**，不依赖 `chat_session.ticket_id`。
- 幂等键贯穿：`idempotency_record(scope=AI_TRIAGE_CONFIRM)` 保存响应；`triage_record.draft_status=CONFIRMED` + `confirmed_at`；`ticket.triage_id` 回指。重复请求按 key 返回同一 `ticketId`。
- 响应同建单接口，返回 `ticketId`、`ticketNo`、`statusCode`。

**POST `/api/v1/ai/triage/{triageId}/cancel`**

- 将 `draft_status` 置 `CANCELLED`；不建单。

### 8.6 相似工单

**POST `/api/v1/ai/similar-tickets`**

```json
{
  "query": "电梯运行异响，停梯检查",
  "topK": 5,
  "filters": {
    "statusCodes": ["CLOSED", "RESOLVED"],
    "priorityCodes": ["URGENT", "NORMAL"],
    "categoryCodes": ["ELEVATOR"],
    "createdFrom": "2026-01-01T00:00:00.000Z",
    "createdTo": "2026-11-17T23:59:59.999Z"
  }
}
```

成功响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "items": [
      {
        "ticketId": "50001",
        "ticketNo": "GD202611170001",
        "title": "A 栋电梯困人",
        "statusCode": "CLOSED",
        "priorityCode": "URGENT",
        "ticketTypeCode": "ELEVATOR",
        "score": 0.86,
        "summary": "电梯困人，维保更换门锁后恢复",
        "closedAt": "2026-11-17T06:00:00.000Z"
      }
    ],
    "degraded": false,
    "traceId": "019a..."
  },
  "traceId": "019a..."
}
```

- Java → Python `/v1/ai/similar/tickets`：Python 查询 ticket collection，返回 `ticket_id` + `score`；Java 批量查询工单并组装。
- **已实现**：建单/流转后异步索引（`vector_object.object_type=TICKET`）；`POST /api/v1/tickets/vector/reindex` 回填历史工单；Agent 新增 `ticket_similar` 工具。
- **Collection 命名**：`agent_gongdan_{type}_{model}_{dim}_v{version}`；工单为 `agent_gongdan_ticket_bge_m3_1024_v1`，知识为 `agent_gongdan_kb_bge_m3_1024_v1`；与 02/03 保持一致。
- **工单向量生成触发**：建单时写入标题+描述向量；完结时补充解决方案向量；dev 引导任务为 10 张演示工单生成初始向量。版本变化产生新 `vector_id`。
- 阈值、topK、结果条数由 `sys_config`：`ai.similar.ticket_threshold`、`ai.similar.recall_top_k`、`ai.similar.result_limit`。
- Milvus 不可用 → `degraded=true`，可回退关键词检索或返回空列表；不得阻断工单主流程。
- **ticket collection 为空 / `vector_index_job` 失败 → 返回空列表 + `degraded=true`，不返回 30006/500**。
- SiliconFlow 整体不可用 → 无检索纯对话；相似工单返回空列表，不报错。
- 权限：`ai:similar`；只返回当前用户有权限查看的工单。

### 8.7 建单溯源

**GET `/api/v1/tickets/{id}/trace`**

响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "ticketId": "50002",
    "source": {
      "type": "CHAT_SESSION",
      "sessionId": "SESS0002",
      "messageId": "MSG0009",
      "toolCallId": "TC0002",
      "triageId": "TRG0002"
    },
    "triage": {
      "intent": "FAULT_REPORT",
      "confidence": 0.88,
      "threshold": 0.75,
      "action": "CREATE_DRAFT",
      "lowConfidence": false,
      "confirmedBy": "10002",
      "confirmedAt": "2026-11-17T08:05:00.000Z"
    },
    "sessions": [
      {
        "sessionId": "SESS0002",
        "relationType": "CREATED",
        "createdToolCallId": "TC0002"
      }
    ],
    "toolCalls": [
      {
        "toolCallId": "TC0002",
        "toolName": "create_ticket",
        "status": "SUCCESS",
        "resultTicketId": "50002"
      }
    ],
    "events": [
      {
        "eventId": "60001",
        "eventType": "CREATED",
        "operatorId": "10002",
        "createdAt": "2026-11-17T08:05:00.000Z"
      }
    ]
  },
  "traceId": "019a..."
}
```

- 数据来源：`ticket.source_ref_type/source_ref_id`、`ticket.triage_id`、`chat_session_ticket`、`chat_tool_call.result_ticket_id`、`ticket_event`。
- 关联查询由 Java 批量查询组装，不使用 JOIN。
- 会话关联一律查询 `chat_session_ticket`；`chat_session.ticket_id` 仅作“最近主工单”展示，不参与溯源。
- 权限：`ticket:read` + 会话访问权限 + `dataScope`；无权限时返回 `10003`。

### 8.8 知识上传、附件与检索

**POST `/api/v1/knowledge/docs`**（multipart）

| 字段 | 必填 | 说明 |
|---|---|---|
| `file` | 是 | pdf/docx/md/png/jpg，≤10MB |
| `title` | 是 | 知识标题 |
| `categoryCode` | 是 | 知识分类 |
| `visibilityType` | 是 | `PUBLIC` / `ROLE` / `DEPT` / `PRIVATE` |
| `visibilitySubjects` | 否 | JSON 数组，角色/部门/用户 ID |

响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "docId": "70001",
    "docNo": "KB0001",
    "status": "UPLOADED",
    "jobNo": "JOB202611170001"
  },
  "traceId": "019a..."
}
```

- 入库异步：Java 创建 `vector_index_job`，调用 Python；状态通过 `GET /api/v1/knowledge/jobs/{jobNo}` 查询。
- 解析/分块/向量化完成后 `status=ACTIVE`；失败 `FAILED` + `errorMessage`。
- 可见性：`kb_doc_visibility` 为权威源；Milvus 标量过滤仅作性能优化。

**附件上传（Q6 闭环）**

**POST `/api/v1/tickets/{id}/attachments`**（multipart）

| 字段 | 必填 | 说明 |
|---|---|---|
| `file` | 是 | 单文件 **≤10MB**；仅允许 **pdf/docx/md/png/jpg** |
| `commentId` | 否 | 关联评论 |
| `attachmentType` | 否 | 默认 `GENERAL` |

- **魔数校验**：服务端读取文件头，校验真实类型与扩展名一致；不一致返回 `60002`；超限返回 `60003`。
- 存储：写入 `file_object` + MinIO bucket `agent-gongdan`；`object_key = agent-gongdan/{env}/{biz_type}/{yyyy/MM}/{uuid}.{ext}`。
- 读取：不直接暴露对象地址；由 Java 生成短时效**预签名 URL**（默认 600s）。
- 响应：`fileId`、`originalName`、`contentType`、`sizeBytes`、`presignedUrl`、`expiresAt`。
- Q6 状态：**已闭环**（10MB、类型白名单、魔数校验、预签名 URL）。

**POST `/api/v1/knowledge/search`**

```json
{
  "query": "电梯困人怎么处理",
  "topK": 5,
  "filters": {
    "categoryCodes": ["ELEVATOR"],
    "visibilityType": "PUBLIC"
  }
}
```

响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "hits": [
      {
        "docId": "70001",
        "chunkId": "80003",
        "title": "电梯困人应急处置手册",
        "score": 0.91,
        "content": "立即安抚被困人员，按警铃……"
      }
    ],
    "degraded": false
  },
  "traceId": "019a..."
}
```

### 8.9 通知与 SLA 预警（P1，不进 MVP 详细契约）

- 通知列表：`GET /api/v1/notifications?unread=true&page=1&pageSize=20`。
- 标记已读：`PUT /api/v1/notifications/{id}/read`、`PUT /api/v1/notifications/read-all`。
- 未读数：`GET /api/v1/notifications/unread-count`。
- **MVP 已实现列表 + 已读 + 未读数轮询（前端 30s）**；`GET /api/v1/notifications/stream` 仍为 P1。
- MVP 仅保留对话 POST SSE；通知通过列表/轮询获取。
- SLA 预警由 Java 定时任务生成；阈值、去重窗口可配置；有效到期按 7.2 的挂起扣除公式。
- 前端 SSE 不可用时回退轮询；不影响工单主流程。

---

## 9. 错误码与异常处理

### 9.1 公共错误码

| 业务码 | HTTP | 错误码 | 含义 | 可重试 | 降级 |
|---:|---:|---|---|---|---|
| 0 | 200 | `SUCCESS` | 成功 | — | — |
| 10001 | 401 | `AUTH_UNAUTHORIZED` | 未登录/令牌无效 | 否 | 否 |
| 10002 | 401 | `AUTH_TOKEN_EXPIRED` | 令牌过期 | 否 | 否 |
| 10003 | 403 | `AUTH_FORBIDDEN` | 无权限 | 否 | 否 |
| 10004 | 401 | `AUTH_LOGIN_FAILED` | 账号或密码错误 | 否 | 否 |
| 10005 | 423 | `AUTH_ACCOUNT_LOCKED` | 账号锁定 | 否 | 否 |
| 10006 | 401 | `AUTH_REFRESH_INVALID` | 刷新令牌无效 | 否 | 否 |
| 20001 | 404 | `TICKET_NOT_FOUND` | 工单不存在 | 否 | 否 |
| 20002 | 409 | `TICKET_STATE_CONFLICT` | 状态/版本冲突 | 否 | 否 |
| 20003 | 409 | `TICKET_ASSIGN_CONFLICT` | 抢单/派单冲突 | 是 | 否 |
| 20004 | 409 | `TICKET_IDEMPOTENCY_CONFLICT` | 幂等键请求体不一致 | 否 | 否 |
| 20005 | 422 | `TICKET_SOURCE_NOT_FOUND` | 来源引用无效 | 否 | 否 |
| 20006 | 409 | `TICKET_HOLD_INVALID` | 挂起/恢复状态非法 | 否 | 否 |
| 30001 | 503 | `AI_UNAVAILABLE` | AI 服务不可用 | 是 | 是 |
| 30002 | 504 | `AI_LLM_TIMEOUT` | 模型超时 | 是 | 是 |
| 30003 | 502 | `AI_TOOL_FAILED` | 工具调用失败 | 视情况 | 是 |
| 30004 | 422 | `AI_AGENT_STEP_LIMIT` | 超出最大步骤 | 否 | 是 |
| 30005 | 502 | `AI_TRIAGE_FAILED` | 分诊失败 | 是 | 是 |
| 30006 | 503 | `AI_SIMILAR_UNAVAILABLE` | 相似工单不可用 | 是 | 是 |
| 30007 | 504 | `AI_STREAM_TIMEOUT` | 流式超时 | 是 | 是 |
| 40001 | 500 | `KB_INGEST_FAILED` | 知识入库失败 | 是 | 否 |
| 40002 | 200 | `KB_NO_HIT` | 无知识命中（业务状态） | 否 | 是 |
| 40003 | 503 | `KB_VECTOR_STORE_ERROR` | 向量库异常 | 是 | 是 |
| 40004 | 404 | `KB_JOB_NOT_FOUND` | 索引任务不存在 | 否 | 否 |
| 50001 | 500 | `SYS_INTERNAL_ERROR` | 未预期错误 | 否 | 否 |
| 50002 | 429 | `SYS_RATE_LIMITED` | 触发限流 | 是 | 是 |
| 50003 | 503 | `SYS_STORAGE_ERROR` | 对象存储异常 | 是 | 是 |
| 50004 | 503 | `SYS_DEPENDENCY_UNAVAILABLE` | 依赖不可用 | 是 | 是 |
| 60001 | 400 | `VALIDATION_ERROR` | 参数校验失败 | 否 | 否 |
| 60002 | 400 | `VALIDATION_FILE_TYPE` | 文件类型不支持 | 否 | 否 |
| 60003 | 413 | `VALIDATION_FILE_SIZE` | 文件超限 | 否 | 否 |
| 60004 | 400 | `VALIDATION_PAGINATION` | 分页参数非法 | 否 | 否 |

### 9.2 与 02 段位对照及内部码完整映射

> 02 附录 B / §7.6 的段位为 `0 / 10xxx / 20xxx / 30xxx / 40xxx / 50xxx / 60xxx`。下表 32 个公共码全部落在既有段位内，**无冲突、无新增段位**。
> 内部 snake_case code 只用于 Java↔Python；Java 必须映射为公共数字码，**禁止透传内部码或堆栈**。

| 内部 snake_case | 公共数字码 | 公共符号 | 02 段位 |
|---|---:|---|---|
| `success` | 0 | `SUCCESS` | 0 |
| `auth_unauthorized` | 10001 | `AUTH_UNAUTHORIZED` | 10xxx |
| `auth_token_expired` | 10002 | `AUTH_TOKEN_EXPIRED` | 10xxx |
| `auth_forbidden` | 10003 | `AUTH_FORBIDDEN` | 10xxx |
| `auth_login_failed` | 10004 | `AUTH_LOGIN_FAILED` | 10xxx |
| `auth_account_locked` | 10005 | `AUTH_ACCOUNT_LOCKED` | 10xxx |
| `auth_refresh_invalid` | 10006 | `AUTH_REFRESH_INVALID` | 10xxx |
| `ticket_not_found` | 20001 | `TICKET_NOT_FOUND` | 20xxx |
| `ticket_state_conflict` | 20002 | `TICKET_STATE_CONFLICT` | 20xxx |
| `ticket_assign_conflict` | 20003 | `TICKET_ASSIGN_CONFLICT` | 20xxx |
| `ticket_idempotency_conflict` | 20004 | `TICKET_IDEMPOTENCY_CONFLICT` | 20xxx |
| `ticket_source_not_found` | 20005 | `TICKET_SOURCE_NOT_FOUND` | 20xxx |
| `ticket_hold_invalid` | 20006 | `TICKET_HOLD_INVALID` | 20xxx |
| `ai_unavailable` | 30001 | `AI_UNAVAILABLE` | 30xxx |
| `ai_llm_timeout` | 30002 | `AI_LLM_TIMEOUT` | 30xxx |
| `ai_tool_failed` | 30003 | `AI_TOOL_FAILED` | 30xxx |
| `ai_agent_step_limit` | 30004 | `AI_AGENT_STEP_LIMIT` | 30xxx |
| `ai_triage_failed` | 30005 | `AI_TRIAGE_FAILED` | 30xxx |
| `ai_similar_unavailable` | 30006 | `AI_SIMILAR_UNAVAILABLE` | 30xxx |
| `ai_stream_timeout` | 30007 | `AI_STREAM_TIMEOUT` | 30xxx |
| `kb_ingest_failed` | 40001 | `KB_INGEST_FAILED` | 40xxx |
| `kb_no_hit` | 40002 | `KB_NO_HIT` | 40xxx |
| `kb_vector_store_error` | 40003 | `KB_VECTOR_STORE_ERROR` | 40xxx |
| `kb_job_not_found` | 40004 | `KB_JOB_NOT_FOUND` | 40xxx |
| `sys_internal_error` | 50001 | `SYS_INTERNAL_ERROR` | 50xxx |
| `sys_rate_limited` | 50002 | `SYS_RATE_LIMITED` | 50xxx |
| `sys_storage_error` | 50003 | `SYS_STORAGE_ERROR` | 50xxx |
| `sys_dependency_unavailable` | 50004 | `SYS_DEPENDENCY_UNAVAILABLE` | 50xxx |
| `validation_error` | 60001 | `VALIDATION_ERROR` | 60xxx |
| `validation_file_type` | 60002 | `VALIDATION_FILE_TYPE` | 60xxx |
| `validation_file_size` | 60003 | `VALIDATION_FILE_SIZE` | 60xxx |
| `validation_pagination` | 60004 | `VALIDATION_PAGINATION` | 60xxx |

**特殊说明**

- 乐观锁冲突使用 `20002` + HTTP 409；响应 `data` 可含 `currentVersion` 提示。
- 流式超时使用 `30007` + HTTP 504；SSE 以 `error` 事件发送，不返回普通 JSON。
- **幂等重放不是错误**：HTTP 200，`Idempotent-Replayed: true`，`code=0`。
- 内部 Token 无效/越权映射为 `10001` / `10003`；不得暴露 `internal_*` 原始码。
- 未知内部码统一映射为 `50001` 并记录原始码到审计日志。

### 9.3 异常处理原则

1. 统一由 Java `GlobalExceptionHandler` 转换；Python 内部错误映射为公共错误码。
2. 不返回堆栈、SQL、内部路径、Token。
3. 业务冲突返回 409；参数错误 400；依赖故障 503/504。
4. 错误响应带 `traceId`；前端提示并提供“复制 traceId”按钮。
5. 可重试错误在响应中标注 `retryable`（内部协议）并由前端决定是否重试。
6. 内部 snake_case code 必须映射为公共数字码；**禁止透传**；未知内部码统一映射为 `50001` 并记录原始码到审计日志。

---

## 10. 超时、重试、熔断与降级

### 10.1 超时预算

| 链路 | 连接 | 读/空闲 | 说明 |
|---|---|---|---|
| 前端普通请求 | — | 10s | 写操作不自动重试 |
| 前端 SSE 对话 | — | 60s 空闲 | 收到 `done/error` 或用户取消 |
| Java → Python 对话 | 1s | 30s / 60s 空闲 | SSE |
| Java → Python 分诊 | 1s | 10s | 同步 |
| Java → Python 相似 | 1s | 8s | 同步 |
| Java → Python 检索 | 1s | 5s | 同步 |
| Java → Python 入库 | 1s | 5s | 返回 202 |
| Python → Java 工具 | 1s | 5s | 单工具 |
| 单次 Agent | — | 45s / 6 步 | 全局预算 |

### 10.2 重试

| 场景 | 次数 | 退避 | 条件 |
|---|---:|---|---|
| Java→Python 连接失败 | 1 | 300ms × 2^n + 抖动 | 仅连接/超时 |
| Java→Python 502/503/504 | 1 | 同上 | 幂等接口 |
| Java→Python SSE | 0 | — | 首字节后禁止重试 |
| Python→Java GET | 2 | 同上 | 连接错误/5xx |
| Python→Java 建单 | 2 | 同上 | 相同 `Idempotency-Key` |
| LLM 调用 | 2 | 500ms × 2^n | 429/5xx/超时；4xx 不重试 |
| Embedding | 2 | 300ms × 2^n | 同上 |

### 10.3 熔断（Java，Resilience4j）

| 参数 | 值 |
|---|---|
| 失败率阈值 | 50% |
| 慢调用阈值 | 3s |
| 打开等待 | 30s |
| 半开放行 | 3 次 |
| 作用范围 | 对话/分诊/相似/检索分别统计 |

### 10.4 降级矩阵

| 故障 | API 行为 | 前端表现 |
|---|---|---|
| Python 不可达 | 对话返回 `30001`；表单建单可用；规则分诊 | 提示 AI 暂不可用，可继续建单 |
| LLM 超时 | SSE `error` 事件 `30002`，`retryable=true`；助手消息 `FAILED` | 提示重试，保留已生成内容 |
| Embedding 不可用 | 跳过向量检索，`degraded=true` | 回答无引用，提示未检索知识 |
| SiliconFlow 整体不可用 | 无检索纯对话；相似返回空列表，不报错 | 回答无引用；相似为空 |
| Milvus 不可用 | 相似工单 `30006` 或空结果；知识检索降级 | 提示知识/相似检索暂不可用 |
| ticket collection 为空/索引失败 | 相似返回空列表 + `degraded=true` | 相似为空，不弹错误 |
| 工具调用失败 | 返回 `30003`；建单幂等可重试 | 提示操作未完成，不产生脏数据 |
| 知识入库失败 | 任务 `FAILED` + `errorMessage` | 可重试/重新索引 |
| 通知 SSE 断开（P1） | 前端回退轮询 | 实时提示暂不可用 |
| Redis 不可用 | refresh/logout 返回 `50004`（fail-closed）；access token 有效至过期 | 提示重新登录或稍后重试 |
| 限流 | `50002` + `Retry-After` | 提示稍后重试 |

---

## 11. 限流、幂等与并发

### 11.1 限流策略

| 维度 | 限制 |
|---|---|
| 普通 API/用户 | 50 QPS，突发 100 |
| 登录/IP | 5 次/分钟 |
| 分诊/用户 | 10 次/分钟 |
| 相似工单/用户 | 20 次/分钟 |
| 知识上传/用户 | 10 次/分钟 |
| SSE/用户 | 2 条并发 |
| SSE/全局 | 200 条并发 |
| 内部接口 | 按实例并发配额 |

### 11.2 幂等与并发

- 建单/确认分诊：`Idempotency-Key` + `idempotency_record` 唯一键；确认分诊与建单使用同一语义的键。
- 幂等重放：返回 HTTP 200、`code=0`、`Idempotent-Replayed: true`；**不是错误**。
- 客户端消息：`(sessionId, clientMessageId)` 唯一。
- 工单状态：`expectedVersion` 乐观锁；冲突 409（`20002`）。
- 抢单：`ticket_claim_pool` 唯一键 + 版本。
- 知识/工单向量：`vector_id` 确定性 + `vector_index_job` 状态机。
- 工具调用：`tool_call_id` 唯一。

### 11.3 会话流并发

- 同一会话同一时间只允许一个活动流；后发请求返回 `20002` 或排队（可配置）。
- `chat_session.stream_expires_at` 作为兜底；超时任务置 `FAILED`。
- 前端重连/重试使用相同 `clientMessageId`，服务端幂等返回。

---

## 12. 安全与审计

### 12.1 传输与 Token

- 生产 HTTPS；开发 HTTP。
- Access Token 仅通过 `Authorization: Bearer` 传输，仅存前端内存。
- Refresh Token 仅 HttpOnly + Secure + SameSite=Lax Cookie；refresh/logout 需 `X-CSRF-Token`。
- 不在 URL、日志、埋点中放置 Token、CSRF token 或内部 Token。
- 内部 Token 按方向拆分（J2P/P2J），支持 current+next 轮换；不暴露给前端。
- **所有 ingress 一致屏蔽 `/internal/**`**：Nginx、Ingress Controller、负载均衡、API 网关均不得转发。
- 开发环境两端绑定 `127.0.0.1`；生产仅私网/安全组互通。

### 12.2 权限与数据范围

- 所有公共接口校验 JWT + 权限码 + 数据范围。
- 内部接口校验方向对应的内部 Token，并复用 Actor 上下文做权限与 `dataScope` 校验。
- 业户/设备/会话/知识按数据范围过滤；`DEPT` 依赖 `sys_dept.path` 前缀索引。
- 越权返回 `10003`，不泄露资源是否存在。

### 12.3 输入与文件安全

- 所有字段校验长度、格式、枚举白名单。
- 文件上传：类型/大小/魔数校验，随机 object_key，预签名短时访问。
- 防提示词注入：用户内容分隔、工具白名单、参数二次校验、禁止执行文档中的指令。
- SQL 注入：MyBatis-Plus 参数化；禁止拼接 SQL。
- XSS：前端 Markdown 渲染使用 DOMPurify；后端不返回可执行 HTML。

### 12.4 审计

- 登录/登出、工单流转、派单、知识变更、AI 工具调用、权限变更均写 `sys_audit_log`。
- 审计包含 `traceId`、Actor、前后值（脱敏）、结果。
- 审计日志只追加，不允许业务接口修改。

---

## 13. 版本演进与契约测试

### 13.1 版本策略

| 变更 | 处理 |
|---|---|
| 新增可选字段 | 兼容，直接发布 |
| 新增枚举值 | 前端需容忍未知值 |
| 字段废弃 | 标记 deprecated，至少保留一个版本周期 |
| 删除/重命名字段 | 升 `/v2` |
| 内部 API 变更 | 双方同步发布；契约测试先行 |

### 13.2 契约测试

| 方向 | 工具/方式 |
|---|---|
| Java → Python | WireMock/Stub 模拟 Python；固定请求/响应样例 |
| Python → Java | respx 模拟 Java 内部 API |
| 公共 API | OpenAPI 快照 + 前端类型生成 |
| SSE | 事件序列快照测试；校验 snake_case→camelCase 映射 |
| 错误码 | 双方共享错误码表；内部码→公共码映射测试；禁止透传 |
| OpenAPI 互校 | CI 校验 Java 公共 spec 与 Python 内部 spec 的契约引用一致 |

### 13.3 OpenAPI 双 Spec

- **Java 公共 API**：`openapi/java-public.yaml`（OpenAPI 3）。
- **Python 内部 API**：`openapi/python-internal.yaml`（OpenAPI 3）。
- 前端 TypeScript 类型由 Java 公共 spec 生成（P1）。
- CI 步骤：
  1. 导出两份 spec；
  2. 校验内部接口路径/字段与 Java `AiGateway` 契约一致；
  3. 校验错误码表与 04 §9 一致；
  4. 校验 SSE 事件枚举一致；
  5. 任一不一致即失败。
- 文档与实际实现不一致视为缺陷。

---

## 14. 附录

### 附录 A：端点统计

| 类别 | 数量 |
|---|---:|
| 认证与用户 | 14 |
| 工单 | 22 |
| AI 会话 | 11 |
| 分诊/相似/溯源 | 5 |
| 知识库 | 10 |
| 派单/通知 | 10 |
| 业户/资源/设备/巡检 | 23 |
| 报表/系统管理 | 14 |
| 健康检查 | 2 |
| **公共 API 合计** | **111** |
| Java → Python 内部 | 9 |
| Python → Java 内部 | 7 |
| **内部 API 合计** | **16** |
| **总计** | **127** |

> MVP 只实现对话 POST SSE；通知/SLA GET SSE 为 P1，不计入 MVP 验收。

### 附录 B：Header 速查

| Header | 适用 | 说明 |
|---|---|---|
| `Authorization` | 公共 API | `Bearer <accessToken>` |
| `Cookie` | 刷新/登出 | refresh token（HttpOnly + Secure + SameSite=Lax） |
| `X-CSRF-Token` | 刷新/登出 | CSRF token，与 `XSRF-TOKEN` Cookie 双提交校验 |
| `X-Internal-Token` | 内部 API | 方向对应 Token（J2P/P2J） |
| `X-Internal-Token-Kid` | 内部 API | `current` / `next`，轮换期使用 |
| `X-Trace-Id` | 全链路 | 追踪 ID |
| `X-Request-Id` | 全链路 | 请求 ID |
| `X-Actor-Id` / `X-Actor-Roles` / `X-Actor-Dept` | Python→Java | Actor 上下文（含 dataScope） |
| `Idempotency-Key` | 建单/确认/入库 | 幂等 |
| `If-None-Match` | P1 | 可选缓存 |
| `Last-Event-ID` | 通知 SSE P1 | 重连回放 |
| `Accept` | SSE | `text/event-stream` |

### 附录 C：SSE 事件速查

| 流 | 事件 |
|---|---|
| 对话（MVP） | `meta`、`delta`、`citation`、`triage`、`tool_call`、`ticket_card`（`state=DRAFT\|CREATED`）、`done`、`error`、`ping` |
| 通知/SLA（P1） | `connected`、`notification`、`sla_warning`、`ticket_update`、`heartbeat` |

> 对话事件全集与 02 §7.2 对齐；不再使用 `ticket_draft`；`triage`/`ping` 已回填 02。

### 附录 D：关键接口权限速查

| 接口 | 权限 |
|---|---|
| 建单 | `ticket:create` |
| 工单查看/溯源 | `ticket:read` |
| 派单/转派 | `ticket:assign` |
| 接单/处理/挂起/解决 | `ticket:handle` |
| 验证 | `ticket:verify` |
| 关闭/取消/重开 | `ticket:close` |
| 分诊 | `ai:triage` |
| 相似工单 | `ai:similar` |
| 会话 | `chat:use` |
| 知识管理 | `knowledge:manage` |
| 派单看板/抢单池 | `dispatch:manage` |
| 报表 | `report:view` |
| 系统管理 | `admin:*` |

### 附录 E：决策追溯

| 决策 | 来源 | 本文档落点 |
|---|---|---|
| Q3 JWT 策略 | 第四阶段拍板 | 3.1 |
| Q4 内部令牌 | 第四阶段拍板 | 3.4 |
| D29 向量映射 | 03 v1.1 | 5、8.6 |
| D30 分诊记录 | 03 v1.1 | 4.4、8.5 |
| D31 工单来源追溯 | 03 v1.1 | 4.2、8.7 |
| D34 会话僵尸清理 | 03 v1.1 | 7.1、11.3 |
| 无物理外键/无 JOIN | 03 v1.1 | 8.3、8.7（代码层组装） |
| D36 JWT/CSRF | 本阶段拍板 | 3.1、3.2 |
| D37 方向拆分内部令牌 | 本阶段拍板 | 3.4 |
| D38 SSE 范围 | 本阶段拍板 | 1.4、7.2 |
| D43 opaque cursor | 本阶段拍板 | 2.5 |
| D44 附件 Q6 | 本阶段拍板 | 8.8 |
| D45 OpenAPI 双 Spec | 本阶段拍板 | 13.3 |

### 附录 F：权限 → 默认角色矩阵

> 第 4 节已给出“端点 → 权限”；本表给出“权限 → 默认角色”。**全量端点三向表见附录 H**。
> `✓` = 默认拥有；`△` = 需结合 dataScope/条件授权；`—` = 默认没有。

| 权限码 | ADMIN | DISPATCHER | ENGINEER | REPORTER | KB_ADMIN | INSPECTOR |
|---|---|---|---|---|---|---|
| `ticket:create` | ✓ | ✓ | ✓ | ✓ | — | ✓ |
| `ticket:read` | ✓ | ✓ | ✓ | ✓ | △ | ✓ |
| `ticket:update` | ✓ | ✓ | ✓ | △ | — | △ |
| `ticket:assign` | ✓ | ✓ | — | — | — | — |
| `ticket:claim` | ✓ | ✓ | ✓ | — | — | △ |
| `ticket:handle` | ✓ | ✓ | ✓ | — | — | △ |
| `ticket:verify` | ✓ | △ | — | ✓ | — | △ |
| `ticket:close` | ✓ | ✓ | △ | — | — | — |
| `ticket:evaluate` | △ | △ | — | ✓ | — | — |
| `chat:use` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `chat:handoff` | ✓ | ✓ | △ | — | — | — |
| `ai:triage` | ✓ | ✓ | ✓ | ✓ | ✓ | △ |
| `ai:similar` | ✓ | ✓ | ✓ | — | ✓ | △ |
| `knowledge:read` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `knowledge:manage` | ✓ | — | — | — | ✓ | — |
| `dispatch:manage` | ✓ | ✓ | — | — | — | — |
| `report:view` | ✓ | ✓ | △ | — | ✓ | — |
| `customer:read` | ✓ | ✓ | △ | △ | — | △ |
| `customer:manage` | ✓ | ✓ | — | — | — | — |
| `equipment:read` | ✓ | ✓ | ✓ | — | — | ✓ |
| `equipment:manage` | ✓ | △ | △ | — | — | △ |
| `admin:user` | ✓ | — | — | — | — | — |
| `admin:role` | ✓ | — | — | — | — | — |
| `admin:config` | ✓ | — | — | — | — | — |
| `admin:audit` | ✓ | — | — | — | — | — |

**dataScope 默认**：`REPORTER=SELF`、`ENGINEER=DEPT`、`DISPATCHER=ALL`、`KB_ADMIN=ALL（知识域）`、`ADMIN=ALL`、`INSPECTOR=SELF`；`DEPT` 依赖 `sys_dept.path` 前缀索引。

### 附录 G：待确认与闭环登记

| 编号 | 事项 | 状态 | 说明 |
|---|---|---|---|
| Q6 | 附件大小/类型 | **已闭环** | 10MB；pdf/docx/md/png/jpg；魔数校验；预签名 URL |
| Q11 | MinIO 使用现有凭据还是独立凭据 | **已确认** | 现有凭据 + 独立 bucket；不修改共享认证/用户/策略 |
| Q12 | Java 包名 | **已确认** | `com.agentgongdan` |
| Q15 | 数据库最小权限账号 | **已确认** | `agent_gongdan_app`（仅本库权限） |
| Q3 | JWT 策略 | **已拍板** | 3.1、3.2 |
| Q4 | 内部令牌 | **已拍板** | 3.4 |
| OpenAPI | 双 Spec + CI 互校 | 已定义 | 13.3 |


### 附录 H：公共 API → 权限 → 默认角色（全量三向表）

> 第 4 节给出端点清单，本表展开每个端点的权限码与默认角色；`△` 表示需结合 dataScope/条件授权。
> 本表覆盖 109 个受权限控制的公共端点 + 2 个健康检查端点 = 111 个公共 API。

| 方法 | 路径 | 权限码 | 默认角色（含 dataScope 条件） |
|---|---|---|---|
| POST | `/api/v1/auth/login` | `公开` | 公开 |
| POST | `/api/v1/auth/refresh` | `Refresh Cookie + CSRF` | 持有 refresh Cookie 的用户 |
| POST | `/api/v1/auth/logout` | `已登录` | 任意已登录 |
| GET | `/api/v1/auth/me` | `已登录` | 任意已登录 |
| PUT | `/api/v1/auth/password` | `已登录` | 任意已登录 |
| GET | `/api/v1/users` | `admin:user` | ADMIN |
| POST | `/api/v1/users` | `admin:user` | ADMIN |
| GET | `/api/v1/users/{id}` | `admin:user` | ADMIN |
| PUT | `/api/v1/users/{id}` | `admin:user` | ADMIN |
| PUT | `/api/v1/users/{id}/status` | `admin:user` | ADMIN |
| PUT | `/api/v1/users/{id}/roles` | `admin:role` | ADMIN |
| GET | `/api/v1/roles` | `admin:role` | ADMIN |
| GET | `/api/v1/permissions` | `admin:role` | ADMIN |
| GET | `/api/v1/depts` | `admin:user` | ADMIN |
| POST | `/api/v1/tickets` | `ticket:create` | ADMIN, DISPATCHER, ENGINEER, REPORTER, INSPECTOR |
| GET | `/api/v1/tickets` | `ticket:read` | ADMIN, DISPATCHER, ENGINEER, REPORTER, KB_ADMIN(△), INSPECTOR |
| GET | `/api/v1/tickets/{id}` | `ticket:read` | ADMIN, DISPATCHER, ENGINEER, REPORTER, KB_ADMIN(△), INSPECTOR |
| PUT | `/api/v1/tickets/{id}` | `ticket:update` | ADMIN, DISPATCHER, ENGINEER, REPORTER(△), INSPECTOR(△) |
| POST | `/api/v1/tickets/{id}/assign` | `ticket:assign` | ADMIN, DISPATCHER |
| POST | `/api/v1/tickets/{id}/claim` | `ticket:claim` | ADMIN, DISPATCHER, ENGINEER, INSPECTOR(△) |
| POST | `/api/v1/tickets/{id}/transfer` | `ticket:assign` | ADMIN, DISPATCHER |
| POST | `/api/v1/tickets/{id}/accept` | `ticket:handle` | ADMIN, DISPATCHER, ENGINEER, INSPECTOR(△) |
| POST | `/api/v1/tickets/{id}/resolve` | `ticket:handle` | ADMIN, DISPATCHER, ENGINEER, INSPECTOR(△) |
| POST | `/api/v1/tickets/{id}/verify` | `ticket:verify` | ADMIN, DISPATCHER(△), REPORTER, INSPECTOR(△) |
| POST | `/api/v1/tickets/{id}/close` | `ticket:close` | ADMIN, DISPATCHER, ENGINEER(△) |
| POST | `/api/v1/tickets/{id}/reopen` | `ticket:close` | ADMIN, DISPATCHER, ENGINEER(△) |
| POST | `/api/v1/tickets/{id}/cancel` | `ticket:close` | ADMIN, DISPATCHER, ENGINEER(△) |
| POST | `/api/v1/tickets/{id}/hold` | `ticket:handle` | ADMIN, DISPATCHER, ENGINEER, INSPECTOR(△) |
| POST | `/api/v1/tickets/{id}/resume` | `ticket:handle` | ADMIN, DISPATCHER, ENGINEER, INSPECTOR(△) |
| GET | `/api/v1/tickets/{id}/events` | `ticket:read` | ADMIN, DISPATCHER, ENGINEER, REPORTER, KB_ADMIN(△), INSPECTOR |
| GET | `/api/v1/tickets/{id}/comments` | `ticket:read` | ADMIN, DISPATCHER, ENGINEER, REPORTER, KB_ADMIN(△), INSPECTOR |
| POST | `/api/v1/tickets/{id}/comments` | `ticket:read` | ADMIN, DISPATCHER, ENGINEER, REPORTER, KB_ADMIN(△), INSPECTOR |
| POST | `/api/v1/tickets/{id}/attachments` | `ticket:update` | ADMIN, DISPATCHER, ENGINEER, REPORTER(△), INSPECTOR(△) |
| DELETE | `/api/v1/tickets/{id}/attachments/{attachmentId}` | `ticket:update` | ADMIN, DISPATCHER, ENGINEER, REPORTER(△), INSPECTOR(△) |
| POST | `/api/v1/tickets/{id}/satisfaction` | `ticket:evaluate` | ADMIN(△), DISPATCHER(△), REPORTER |
| GET | `/api/v1/tickets/{id}/trace` | `ticket:read` | ADMIN, DISPATCHER, ENGINEER, REPORTER, KB_ADMIN(△), INSPECTOR |
| POST | `/api/v1/chat/sessions` | `chat:use` | 任意已登录 |
| GET | `/api/v1/chat/sessions` | `chat:use` | 任意已登录 |
| GET | `/api/v1/chat/sessions/{id}` | `chat:use` | 任意已登录 |
| DELETE | `/api/v1/chat/sessions/{id}` | `chat:use` | 任意已登录 |
| GET | `/api/v1/chat/sessions/{id}/messages` | `chat:use` | 任意已登录 |
| POST | `/api/v1/chat/sessions/{id}/messages` | `chat:use` | 任意已登录 |
| POST | `/api/v1/chat/sessions/{id}/close` | `chat:use` | 任意已登录 |
| POST | `/api/v1/chat/sessions/{id}/handoff` | `chat:handoff` | ADMIN, DISPATCHER, ENGINEER(△) |
| GET | `/api/v1/chat/sessions/{id}/tickets` | `chat:use` | 任意已登录 |
| GET | `/api/v1/chat/sessions/{id}/triage-records` | `chat:use` | 任意已登录 |
| POST | `/api/v1/chat/messages/{messageId}/feedback` | `chat:use` | 任意已登录 |
| POST | `/api/v1/ai/triage` | `ai:triage` | ADMIN, DISPATCHER, ENGINEER, REPORTER, KB_ADMIN, INSPECTOR(△) |
| GET | `/api/v1/ai/triage/{triageId}` | `ai:triage` | ADMIN, DISPATCHER, ENGINEER, REPORTER, KB_ADMIN, INSPECTOR(△) |
| POST | `/api/v1/ai/triage/{triageId}/confirm` | `ticket:create` | ADMIN, DISPATCHER, ENGINEER, REPORTER, INSPECTOR |
| POST | `/api/v1/ai/triage/{triageId}/cancel` | `ai:triage` | ADMIN, DISPATCHER, ENGINEER, REPORTER, KB_ADMIN, INSPECTOR(△) |
| POST | `/api/v1/ai/similar-tickets` | `ai:similar` | ADMIN, DISPATCHER, ENGINEER, KB_ADMIN, INSPECTOR(△) |
| POST | `/api/v1/knowledge/docs` | `knowledge:manage` | ADMIN, KB_ADMIN |
| GET | `/api/v1/knowledge/docs` | `knowledge:read` | 任意已登录 |
| GET | `/api/v1/knowledge/docs/{id}` | `knowledge:read` | 任意已登录 |
| PUT | `/api/v1/knowledge/docs/{id}` | `knowledge:manage` | ADMIN, KB_ADMIN |
| DELETE | `/api/v1/knowledge/docs/{id}` | `knowledge:manage` | ADMIN, KB_ADMIN |
| POST | `/api/v1/knowledge/docs/{id}/reindex` | `knowledge:manage` | ADMIN, KB_ADMIN |
| PUT | `/api/v1/knowledge/docs/{id}/visibility` | `knowledge:manage` | ADMIN, KB_ADMIN |
| GET | `/api/v1/knowledge/docs/{id}/chunks` | `knowledge:read` | 任意已登录 |
| GET | `/api/v1/knowledge/jobs/{jobNo}` | `knowledge:read` | 任意已登录 |
| POST | `/api/v1/knowledge/search` | `knowledge:read` | 任意已登录 |
| GET | `/api/v1/dispatch/board` | `dispatch:manage` | ADMIN, DISPATCHER |
| GET | `/api/v1/dispatch/claim-pool` | `dispatch:manage` | ADMIN, DISPATCHER |
| POST | `/api/v1/dispatch/claim-pool/{ticketId}/claim` | `ticket:claim` | ADMIN, DISPATCHER, ENGINEER, INSPECTOR(△) |
| POST | `/api/v1/dispatch/claim-pool/{ticketId}/release` | `dispatch:manage` | ADMIN, DISPATCHER |
| GET | `/api/v1/notifications` | `已登录` | 任意已登录 |
| PUT | `/api/v1/notifications/{id}/read` | `已登录` | 任意已登录 |
| PUT | `/api/v1/notifications/read-all` | `已登录` | 任意已登录 |
| GET | `/api/v1/notifications/stream` | `已登录` | 任意已登录 |
| GET | `/api/v1/notifications/preferences` | `已登录` | 任意已登录 |
| PUT | `/api/v1/notifications/preferences` | `已登录` | 任意已登录 |
| GET | `/api/v1/customers` | `customer:read` | ADMIN, DISPATCHER, ENGINEER(△), REPORTER(△), INSPECTOR(△) |
| POST | `/api/v1/customers` | `customer:manage` | ADMIN, DISPATCHER |
| GET | `/api/v1/customers/{id}` | `customer:read` | ADMIN, DISPATCHER, ENGINEER(△), REPORTER(△), INSPECTOR(△) |
| PUT | `/api/v1/customers/{id}` | `customer:manage` | ADMIN, DISPATCHER |
| DELETE | `/api/v1/customers/{id}` | `customer:manage` | ADMIN, DISPATCHER |
| GET | `/api/v1/resources/buildings` | `customer:read` | ADMIN, DISPATCHER, ENGINEER(△), REPORTER(△), INSPECTOR(△) |
| GET | `/api/v1/resources/rooms` | `customer:read` | ADMIN, DISPATCHER, ENGINEER(△), REPORTER(△), INSPECTOR(△) |
| POST | `/api/v1/resources/rooms` | `customer:manage` | ADMIN, DISPATCHER |
| GET | `/api/v1/resources/map` | `customer:read` | ADMIN, DISPATCHER, ENGINEER(△), REPORTER(△), INSPECTOR(△) |
| GET | `/api/v1/resources/tenancies` | `customer:read` | ADMIN, DISPATCHER, ENGINEER(△), REPORTER(△), INSPECTOR(△) |
| POST | `/api/v1/resources/tenancies` | `customer:manage` | ADMIN, DISPATCHER |
| GET | `/api/v1/equipment-categories` | `equipment:read` | ADMIN, DISPATCHER, ENGINEER, INSPECTOR |
| POST | `/api/v1/equipment-categories` | `equipment:manage` | ADMIN, DISPATCHER(△), ENGINEER(△), INSPECTOR(△) |
| GET | `/api/v1/equipment` | `equipment:read` | ADMIN, DISPATCHER, ENGINEER, INSPECTOR |
| POST | `/api/v1/equipment` | `equipment:manage` | ADMIN, DISPATCHER(△), ENGINEER(△), INSPECTOR(△) |
| GET | `/api/v1/equipment/{id}` | `equipment:read` | ADMIN, DISPATCHER, ENGINEER, INSPECTOR |
| PUT | `/api/v1/equipment/{id}` | `equipment:manage` | ADMIN, DISPATCHER(△), ENGINEER(△), INSPECTOR(△) |
| DELETE | `/api/v1/equipment/{id}` | `equipment:manage` | ADMIN, DISPATCHER(△), ENGINEER(△), INSPECTOR(△) |
| GET | `/api/v1/equipment/{id}/history` | `equipment:read` | ADMIN, DISPATCHER, ENGINEER, INSPECTOR |
| GET | `/api/v1/inspection/plans` | `equipment:read` | ADMIN, DISPATCHER, ENGINEER, INSPECTOR |
| POST | `/api/v1/inspection/plans` | `equipment:manage` | ADMIN, DISPATCHER(△), ENGINEER(△), INSPECTOR(△) |
| GET | `/api/v1/inspection/tasks` | `equipment:read` | ADMIN, DISPATCHER, ENGINEER, INSPECTOR |
| POST | `/api/v1/inspection/tasks/{id}/execute` | `equipment:manage` | ADMIN, DISPATCHER(△), ENGINEER(△), INSPECTOR(△) |
| GET | `/api/v1/reports/tickets/overview` | `report:view` | ADMIN, DISPATCHER, ENGINEER(△), KB_ADMIN |
| GET | `/api/v1/reports/tickets/trend` | `report:view` | ADMIN, DISPATCHER, ENGINEER(△), KB_ADMIN |
| GET | `/api/v1/reports/sla` | `report:view` | ADMIN, DISPATCHER, ENGINEER(△), KB_ADMIN |
| GET | `/api/v1/reports/satisfaction` | `report:view` | ADMIN, DISPATCHER, ENGINEER(△), KB_ADMIN |
| GET | `/api/v1/reports/ai` | `report:view` | ADMIN, DISPATCHER, ENGINEER(△), KB_ADMIN |
| GET | `/api/v1/admin/dicts/{typeCode}` | `admin:config` | ADMIN |
| POST | `/api/v1/admin/dicts/{typeCode}/items` | `admin:config` | ADMIN |
| PUT | `/api/v1/admin/dicts/items/{id}` | `admin:config` | ADMIN |
| DELETE | `/api/v1/admin/dicts/items/{id}` | `admin:config` | ADMIN |
| GET | `/api/v1/admin/configs` | `admin:config` | ADMIN |
| PUT | `/api/v1/admin/configs/{key}` | `admin:config` | ADMIN |
| GET | `/api/v1/admin/audit-logs` | `admin:audit` | ADMIN |
| GET | `/api/v1/admin/ai-configs` | `admin:config` | ADMIN |
| PUT | `/api/v1/admin/ai-configs` | `admin:config` | ADMIN |

| GET | `/api/v1/health` | `公开/内网` | 公开/内网 |
| GET | `/api/v1/ready` | `内网` | 内网 |


---

> **文档结束**。本文档不包含任何业务代码、控制器实现或脚手架；05 已定稿，后续按 §4.10 维护实现状态对账。
