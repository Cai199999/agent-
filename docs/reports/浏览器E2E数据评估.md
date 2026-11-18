# 浏览器级 E2E 数据评估

> 日期：2026-11-18
> 目标：评估 Playwright 浏览器级 E2E 所需数据、环境与副作用。

## 1. 环境要求

| 项 | 要求 |
|---|---|
| Web | `http://127.0.0.1:5180` |
| Java | `http://127.0.0.1:8200` |
| Python | `http://127.0.0.1:8300` |
| 浏览器 | Playwright Chromium；当前使用 `~/.cache/ms-playwright/chromium-1254/chrome-linux64/chrome` |
| 账号 | `admin`（主流程）、`engineer1`（处理流程）、`reporter1`（权限/个人中心） |
| 密码 | 项目根 `.env` 的 `DEV_ADMIN_PASSWORD` |
| 数据 | MySQL `agent_gongdan_db`、MinIO `agent-gongdan` |

可使用环境变量覆盖：

```bash
PLAYWRIGHT_CHROMIUM_PATH=/path/to/chrome
E2E_ADMIN_USER=admin
E2E_ADMIN_PASSWORD=...
```

## 2. 当前数据是否足够

| 数据 | 当前情况 | 结论 |
|---|---|---|
| 用户 | admin / engineer1 / reporter1 | ✅ 足够 |
| 主数据 | 1 项目、7 楼宇、10 房间、3 客户、5 设备、5 服务项 | ✅ 足够建单 |
| 管理员个人资料 | 已审核 `APPROVED`，项目/楼宇/房间/客户已绑定 | ✅ 建单可自动带出 |
| 工单 | 30+ 条，覆盖多状态 | ✅ 列表/详情/导出可用 |
| 知识文档 | 4 篇 ACTIVE（电梯/水泵/门禁/空调） | ✅ RAG 场景可用 |
| 现场图片 | E2E fixture `apps/web/e2e/fixtures/onsite.png` | ✅ 上传/现场图校验可用 |
| 回访/材料 | 已有测试记录 | ✅ 详情展示可用 |
| PII 密钥 | `.env` 已配置 `PII_ENC_KEY` | ✅ 资料电话加密可用 |

**结论：当前数据足够支撑登录、个人中心、简化建单、图片上传、工单列表/详情、导出等浏览器 E2E。**
聊天 SSE、派单/接单/解决、材料/回访的完整浏览器链路仍建议后续补，因为需要更稳定的测试账号和状态数据。

## 3. 测试数据策略

| 测试 | 数据操作 | 副作用 |
|---|---|---|
| 登录 | 使用 admin | 无 |
| 个人中心 | 打开并校验表单，不修改 | 无 |
| 简化建单 | 新建一条带图片的室内工单 | 新增 1 条工单 + 1 个附件 |
| 工单详情 | 读取已有工单 | 无 |
| 导出 | 只读 | 无 |

- 工单标题带时间戳，避免重复；
- 不删除既有工单、知识、用户；
- 不测试破坏性操作；
- 不在浏览器测试中停 Python/共享中间件。

## 4. 已实现用例

```text
e2e/auth.spec.ts            登录 → 工单列表
e2e/profile.spec.ts         个人中心加载
e2e/ticket-create.spec.ts   简化报修表单 + 楼宇/房间 + 图片 → 建单
```

运行：

```bash
cd apps/web
npm run e2e
```

## 5. 当前覆盖边界

已覆盖：

- 登录与路由跳转；
- 工单管理页；
- 个人中心；
- 室内/室外表单结构；
- 楼宇/房间级联；
- 图片上传；
- 建单成功提示。

未覆盖（需后续补）：

- 手机摄像头真实调起；
- AI 对话 SSE 流式渲染；
- 分诊草稿确认；
- 派单/接单/解决/现场照片强制完整浏览器流程；
- 材料/回访表单；
- 导出文件下载断言。

## 6. 风险

1. Playwright 浏览器版本与缓存版本可能不一致，需 `PLAYWRIGHT_CHROMIUM_PATH` 指向可用 Chromium；
2. 浏览器测试会真实创建工单和附件，建议使用 dev 库；
3. 手机拍摄无法在 headless 浏览器完整模拟，需要人工真机验收；
4. 测试依赖 admin 个人资料为 `APPROVED`；若被改为 `PENDING`，建单自动带出会失效。
