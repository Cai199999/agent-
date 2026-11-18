#!/usr/bin/env python3
"""TD09 多轮真实场景模拟与观测数据汇总。

模拟 3 个用户 × 10 轮对话，覆盖知识检索、工单查询、分诊草稿等场景；
统计 chat.context / chat.done / chat.tool 日志，生成
docs/reports/TD09观测数据简报.md。

运行：
    apps/python-ai/.venv/bin/python scripts/e2e/td09_multiturn.py

注意：
- 仅调用 Java BFF，不直连数据库；
- 不确认工单草稿，避免批量产生工单；
- 日志从运行开始时已有的行数之后读取，避免混入历史测试数据。
"""

from __future__ import annotations

import json
import re
import statistics
import sys
import time
import uuid
from collections import Counter
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parents[2]
BASE = "http://127.0.0.1:8200/api/v1"
JAVA_LOG = Path("/tmp/agent-gongdan-java.log")
PYTHON_LOG = Path("/tmp/agent-gongdan-python.log")
REPORT = ROOT / "docs" / "reports" / "TD09观测数据简报.md"

USERS = ["admin", "engineer1", "reporter1"]
PROMPTS = [
    "A栋2号梯困人了，请尽快处理",
    "电梯困人现场第一步应该做什么？",
    "帮我查一下我的工单",
    "最近有没有类似电梯困人的历史工单？",
    "消防水泵多久巡检一次？",
    "请把电梯困人处理流程分步骤说明",
    "A栋大堂门禁打不开，怎么处理？",
    "检索知识库：电梯困人",
    "帮我查一下最近的工单",
    "谢谢，请总结上面提到的处置步骤",
]


def load_env() -> dict[str, str]:
    values: dict[str, str] = {}
    path = ROOT / ".env"
    if not path.exists():
        return values
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


ENV = load_env()
PASSWORD = ENV.get("DEV_ADMIN_PASSWORD", "")


def line_count(path: Path) -> int:
    if not path.exists():
        return 0
    with path.open("rb") as handle:
        return sum(1 for _ in handle)


def login(client: httpx.Client, username: str) -> tuple[str | None, str]:
    response = client.post(f"{BASE}/auth/login",
                           json={"username": username, "password": PASSWORD})
    try:
        body = response.json()
    except Exception:
        body = {}
    token = (body.get("data") or {}).get("accessToken")
    return token, body.get("message", "")


def create_session(client: httpx.Client, token: str) -> str | None:
    response = client.post(f"{BASE}/chat/sessions",
                           headers={"Authorization": f"Bearer {token}"}, json={})
    body = response.json()
    return str((body.get("data") or {}).get("id") or "") or None


def chat(client: httpx.Client, token: str, session_id: str, content: str) -> dict:
    events: dict[str, int] = {}
    error = ""
    payload = {"clientMessageId": f"td09-{uuid.uuid4().hex}",
               "content": content, "contentType": "TEXT"}
    headers = {"Authorization": f"Bearer {token}", "Accept": "text/event-stream"}
    try:
        with client.stream("POST", f"{BASE}/chat/sessions/{session_id}/messages",
                           headers=headers, json=payload, timeout=120.0) as response:
            response.raise_for_status()
            event_name, data_lines = "", []
            for line in response.iter_lines():
                if line.startswith("event:"):
                    event_name = line[6:].strip()
                elif line.startswith("data:"):
                    data_lines.append(line[5:].strip())
                elif line.strip() == "":
                    if event_name:
                        events[event_name] = events.get(event_name, 0) + 1
                        if event_name == "error" and data_lines:
                            try:
                                error = json.loads("\n".join(data_lines)).get("message", "")
                            except json.JSONDecodeError:
                                error = "error event"
                    event_name, data_lines = "", []
    except Exception as exc:  # noqa: BLE001
        error = str(exc)
    return {"events": events, "error": error}


def parse_logs(java_start: int, python_start: int) -> dict:
    java_lines = JAVA_LOG.read_text(encoding="utf-8", errors="replace").splitlines()[java_start:]
    python_lines = PYTHON_LOG.read_text(encoding="utf-8", errors="replace").splitlines()[python_start:]
    java = "\n".join(java_lines)
    python = "\n".join(python_lines)
    contexts = [
        (int(h), int(c), int(chars), deg == "true")
        for h, c, chars, deg in re.findall(
            r"chat\.context .*?history=(\d+) chunks=(\d+) contextChars=(\d+) degraded=(\w+)", java)
    ]
    dones = [
        (int(i), int(o), int(lat))
        for i, o, lat in re.findall(
            r"chat\.done .*?inputTokens=(\d+) outputTokens=(\d+) latencyMs=(\d+)", java)
    ]
    tools = [
        (name, status, int(size), trunc == "true")
        for name, status, size, trunc in re.findall(
            r"chat\.tool .*?tool=(\S+) status=(\S+) resultBytes=(\d+) truncated=(\w+)", java)
    ]
    return {
        "contexts": contexts,
        "dones": dones,
        "tools": tools,
        "errors": len(re.findall(r"chat\.(?:error|stream_error)", java)),
        "truncated_logs": len(re.findall(r"tool_result\.truncated", python)),
        "python_contexts": len(re.findall(r"chat\.context", python)),
    }


def write_report(stats: dict, runs: list[dict]) -> None:
    contexts = stats["contexts"]
    dones = stats["dones"]
    tools = stats["tools"]
    context_chars = [item[2] for item in contexts]
    histories = [item[0] for item in contexts]
    chunks = [item[1] for item in contexts]
    degraded = sum(1 for item in contexts if item[3])
    latencies = [item[2] for item in dones]
    input_tokens = [item[0] for item in dones]
    output_tokens = [item[1] for item in dones]

    def avg(values: list[int]) -> str:
        return f"{statistics.mean(values):.1f}" if values else "-"

    def p95(values: list[int]) -> str:
        if not values:
            return "-"
        ordered = sorted(values)
        index = min(len(ordered) - 1, int(len(ordered) * 0.95))
        return str(ordered[index])

    history_dist = Counter(histories)
    tool_names = Counter(name for name, _, _, _ in tools)
    truncated = sum(1 for _, _, _, flag in tools if flag)
    max_tool = max((size for _, _, size, _ in tools), default=0)
    errors = sum(1 for run in runs if run["error"])

    lines = [
        "# TD09 观测数据简报",
        "",
        "> 项目：IT 运维工单智能 Agent（agent-gongdan）",
        "> 日期：2026-11-18",
        "> 数据来源：本地真实调用（Java/Python 日志）",
        "> 样本：3 个用户 × 10 轮对话",
        "",
        "## 1. 样本与方法",
        "",
        "- 用户：`admin`、`engineer1`、`reporter1`",
        "- 每用户 1 个会话、10 轮；覆盖知识检索、工单查询、分诊草稿、相似工单等场景",
        "- 未确认工单草稿，避免批量建单",
        "- 仅统计本次运行新增日志，不含历史测试记录",
        "",
        "## 2. 核心指标",
        "",
        "| 指标 | 结果 |",
        "|---|---|",
        f"| 完成对话轮次 | {len(dones)} |",
        f"| 对话错误数 | {errors} |",
        f"| `chat.context` 样本数 | {len(contexts)} |",
        f"| `contextChars` 平均 / 最大 | {avg(context_chars)} / {max(context_chars, default=0)} |",
        f"| `contextChars` P95 | {p95(context_chars)} |",
        f"| `history` 平均 / 最大 | {avg(histories)} / {max(histories, default=0)} |",
        f"| `chunks` 平均 / 最大 | {avg(chunks)} / {max(chunks, default=0)} |",
        f"| `degraded` 次数 | {degraded} |",
        f"| 输入 token 总计 / 平均 | {sum(input_tokens)} / {avg(input_tokens)} |",
        f"| 输出 token 总计 / 平均 | {sum(output_tokens)} / {avg(output_tokens)} |",
        f"| 延迟平均 / 最大 / P95 | {avg(latencies)}ms / {max(latencies, default=0)}ms / {p95(latencies)}ms |",
        f"| 工具调用次数 | {len(tools)} |",
        f"| 工具结果最大字节数 | {max_tool} |",
        f"| 工具结果截断次数 | {truncated} |",
        f"| Python `tool_result.truncated` 日志 | {stats['truncated_logs']} |",
        "",
        "## 3. History 分布",
        "",
        "| history 条数 | 次数 |",
        "|---|---|",
    ]
    for value in sorted(history_dist):
        lines.append(f"| {value} | {history_dist[value]} |")
    lines += [
        "",
        "## 4. 工具调用",
        "",
        "| 工具 | 次数 |",
        "|---|---|",
    ]
    if tool_names:
        for name, count in tool_names.most_common():
            lines.append(f"| `{name}` | {count} |")
    else:
        lines.append("| 无 | 0 |")
    lines += [
        "",
        "## 5. 结论",
        "",
    ]
    if not contexts:
        lines.append("- 本次未采集到 `chat.context`，请检查 Java 日志路径与应用启动状态。")
    else:
        lines.append(
            f"- 本次 `contextChars` 平均 {avg(context_chars)}、最大 {max(context_chars, default=0)}，"
            f"未接近 `RAG_MAX_CONTEXT_CHARS=6000`。"
        )
        lines.append(
            f"- `history` 最大 {max(histories, default=0)} 条，"
            f"{'已达到' if max(histories, default=0) >= 20 else '尚未达到'}当前 20 条滑动窗口上限。"
        )
        lines.append(
            f"- 工具结果最大 {max_tool} 字节，截断 {truncated} 次；"
            f"{'已触发' if truncated or stats['truncated_logs'] else '未触发'}硬截断。"
        )
        lines.append(
            f"- 对话错误 {errors} 次；`degraded` {degraded} 次。"
        )
        lines.append(
            "- 当前样本下，TD10（滚动摘要）**没有紧急触发信号**；建议继续积累真实用户长会话，"
            "重点观察 history=20 的比例、contextChars>4000 的比例和工具截断率。"
        )
    lines += [
        "",
        "## 6. 复现",
        "",
        "```bash",
        "apps/python-ai/.venv/bin/python scripts/e2e/td09_multiturn.py",
        "```",
        "",
        "> 说明：本简报为一次本地模拟样本，不代表生产分布；参数阈值需在真实用户数据上复评。",
        "",
    ]
    REPORT.write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    if not PASSWORD:
        print("缺少 DEV_ADMIN_PASSWORD，无法登录", file=sys.stderr)
        return 1
    java_start = line_count(JAVA_LOG)
    python_start = line_count(PYTHON_LOG)
    runs: list[dict] = []
    print(f"start java_line={java_start} python_line={python_start}")
    with httpx.Client(timeout=60.0) as client:
        for user in USERS:
            token, message = login(client, user)
            if not token:
                print(f"login failed user={user} message={message}")
                runs.append({"user": user, "error": message, "events": {}})
                continue
            session_id = create_session(client, token)
            if not session_id:
                print(f"create session failed user={user}")
                runs.append({"user": user, "error": "create session failed", "events": {}})
                continue
            print(f"user={user} session={session_id}")
            for index, prompt in enumerate(PROMPTS, start=1):
                result = chat(client, token, session_id, prompt)
                runs.append({"user": user, "turn": index, **result})
                print(f"  turn={index:02d} events={result['events']} error={result['error'][:60]}")
                time.sleep(0.3)
    stats = parse_logs(java_start, python_start)
    write_report(stats, runs)
    print(f"report={REPORT}")
    print(f"contexts={len(stats['contexts'])} dones={len(stats['dones'])} tools={len(stats['tools'])}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
