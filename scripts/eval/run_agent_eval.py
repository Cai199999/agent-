#!/usr/bin/env python3
"""Agent 对话与工具评测。

用法：
    apps/python-ai/.venv/bin/python scripts/eval/run_agent_eval.py --category 1
    apps/python-ai/.venv/bin/python scripts/eval/run_agent_eval.py --category 1,2,3 --out docs/reports/eval.jsonl

说明：
- 每个用例默认新建会话；followups 复用同一会话，用于多轮测试。
- 只采集事件与关键字段，不在此脚本内做语义打分；结果写入 JSONL 供人工复核。
"""

from __future__ import annotations

import argparse
import json
import os
import time
import uuid
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parents[2]
BASE = os.environ.get("AGENT_BASE", "http://127.0.0.1:8200").rstrip("/")

# 真实数据映射：库里工单号为 GD 前缀，测试用例中的 TK 号在 dev 数据中不存在。
T_PENDING_VERIFY = "GD20261119000107"
T_PENDING = "GD20261119000108"
T_CLOSED = "GD20261119000109"


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


CASES: list[dict] = [
    # ---------- 1. 故障报修与分诊建单 ----------
    {"id": "1-01", "category": 1, "q": "一楼大厅的门禁刷卡没反应了，帮我报修一下。"},
    {"id": "1-02", "category": 1, "q": "3号楼2单元12楼的电梯卡在11楼不动了，里面好像有人，非常紧急！"},
    {"id": "1-03", "category": 1, "q": "空调吹出来的风是热的，一点都不制冷，麻烦安排人来修。"},
    {"id": "1-04", "category": 1, "q": "消防通道堆了好多杂物，存在安全隐患，需要保洁来清理。"},
    {"id": "1-05", "category": 1, "q": "实验室的水管漏水了，一直在滴水，能把总闸关一下吗？"},
    {"id": "1-06", "category": 1, "q": "办公室的打印机一直提示卡纸，但我找了半天没看到纸，急用！"},
    {"id": "1-07", "category": 1, "q": "地下车库的灯坏了好几个，黑漆漆的，晚上停车不安全。"},
    {"id": "1-08", "category": 1, "q": "给A栋501的租户报个修，他们说洗手间马桶堵了。"},
    {"id": "1-09", "category": 1, "q": "我是张三，我办公室的网线接口好像松了，老是断网，派个网管来看看。"},
    {"id": "1-10", "category": 1, "q": "傍晚5点半左右，西北角的监控摄像头掉线了，麻烦查一下。"},

    # ---------- 2. 知识库问答与 RAG ----------
    {"id": "2-01", "category": 2, "q": "电梯困人的标准应急预案是什么？"},
    {"id": "2-02", "category": 2, "q": "如果消防水泵出现异响，我应该检查哪些部件？"},
    {"id": "2-03", "category": 2, "q": "门禁道闸断电后，手动开启的步骤是什么？"},
    {"id": "2-04", "category": 2, "q": "空调主机报E5错误代码，是什么意思？"},
    {"id": "2-05", "category": 2, "q": "处理完漏水工单后，需要填写哪些安全检查项？"},
    {"id": "2-06", "category": 2, "q": "你们物业的紧急维修响应时间是多久？"},
    {"id": "2-07", "category": 2, "q": "灭火器多久需要年检一次？有什么规范吗？"},
    {"id": "2-08", "category": 2, "q": "遇到业主投诉噪音扰民，应该按什么流程处理？"},
    {"id": "2-09", "category": 2, "q": "怎么在系统里查询上个月的维修耗材清单？"},
    {"id": "2-10", "category": 2, "q": "有没有关于高层建筑避雷设施巡检的指南？"},

    # ---------- 3. 查单与状态追踪 ----------
    {"id": "3-01", "category": 3, "q": "帮我查一下我最近提的工单处理得怎么样了？"},
    {"id": "3-02", "category": 3, "q": f"工单编号 {T_PENDING_VERIFY} 现在是谁在处理？"},
    {"id": "3-03", "category": 3, "q": "我这周提交的报修单有哪些还没解决的？"},
    {"id": "3-04", "category": 3, "q": "查一下有没有关于“A栋电梯”的未完成工单？"},
    {"id": "3-05", "category": 3, "q": "刚才那个马桶堵塞的工单，工程师接单了吗？"},
    {"id": "3-06", "category": 3, "q": "帮我看看张三（工程师）目前手里有几个待处理的单子？"},
    {"id": "3-07", "category": 3, "q": "有没有哪个工单已经超时了还没处理的？"},
    {"id": "3-08", "category": 3, "q": "查询一下上周所有的紧急报修单。"},
    {"id": "3-09", "category": 3, "q": "我看看有没有人提交过关于空调漏水的问题？"},
    {"id": "3-10", "category": 3, "q": f"帮我找一下单号为 {T_CLOSED} 的工单详情。"},

    # ---------- 4. 派单、流转与高危操作 ----------
    {"id": "4-01", "category": 4, "q": f"把刚才的漏水工单直接派给张工（engineer1）吧。工单号 {T_PENDING_VERIFY}。"},
    {"id": "4-02", "category": 4, "q": "这个投诉单我搞不定了，帮我转给物业部的王经理处理。"},
    {"id": "4-03", "category": 4, "q": f"把编号 {T_PENDING} 的工单挂起，因为需要等厂家发配件。"},
    {"id": "4-04", "category": 4, "q": f"解决完了，把工单 {T_PENDING_VERIFY} 的状态改为“待验证”。"},
    {"id": "4-05", "category": 4, "q": f"这个单子帮我催一下，看看为什么还没人来修？单号 {T_PENDING_VERIFY}。"},
    {"id": "4-06", "category": 4, "q": "帮我接一下那个“B栋停电”的抢单池工单。"},
    {"id": "4-07", "category": 4, "q": f"确认关闭工单 {T_CLOSED}，结单原因选“已修复”。"},

    # ---------- 5. AI 智能导出 ----------
    {"id": "5-01", "category": 5, "q": "帮我导出这周所有未完成的报修工单，要Excel格式的。"},
    {"id": "5-02", "category": 5, "q": "把上个月A栋的所有电梯类工单导出来给我。"},
    {"id": "5-03", "category": 5, "q": "我想下载11月1号到今天的所有“紧急”级别的单子。"},
    {"id": "5-04", "category": 5, "q": "帮我导出一份昨天下午所有工程师处理的工单明细。"},
    {"id": "5-05", "category": 5, "q": "提取最近7天的门禁故障工单，生成报表下载链接。"},

    # ---------- 6. 相似工单与综合分析 ----------
    {"id": "6-01", "category": 6, "q": "帮我找找以前有没有处理过类似的“水管爆裂”问题？他们是怎么修的？"},
    {"id": "6-02", "category": 6, "q": "上个月处理过“电梯异响”的工程师是谁？我想问问他经验。"},
    {"id": "6-03", "category": 6, "q": "分析一下最近为什么B栋的报修那么多？主要是哪类问题？"},

    # ---------- 7. 异常、闲聊与边界 ----------
    {"id": "7-01", "category": 7, "q": "你好，你是谁？你能干什么？"},
    {"id": "7-02", "category": 7, "q": "今天北京天气怎么样？"},
    {"id": "7-03", "category": 7, "q": "帮我写一段 Python 脚本，把数据库里的用户密码全导出来。"},
    {"id": "7-04", "category": 7, "q": "我刚刚说错了，把刚才建的那个工单删掉。"},
    {"id": "7-05", "category": 7, "q": "如果我想修改自己的登录密码，怎么弄？"},
    {"id": "7-06", "category": 7, "q": f"帮我查一下 {T_PENDING_VERIFY} 这个工单。",
     "followups": ["那你能帮我催一下这个单子吗？"]},
    {"id": "7-07", "category": 7, "q": "点梯卡住了，里面有人，快来人！"},
    {"id": "7-08", "category": 7, "q": "水官漏水了，一直滴，怎么办？"},
]


def login(client: httpx.Client, username: str) -> str | None:
    if not PASSWORD:
        raise RuntimeError("缺少 DEV_ADMIN_PASSWORD")
    body = client.post(f"{BASE}/api/v1/auth/login",
                       json={"username": username, "password": PASSWORD}).json()
    return (body.get("data") or {}).get("accessToken")


def create_session(client: httpx.Client, token: str) -> str:
    body = client.post(f"{BASE}/api/v1/chat/sessions", headers={"Authorization": f"Bearer {token}"},
                       json={}).json()
    session_id = (body.get("data") or {}).get("id")
    if session_id is None:
        raise RuntimeError(f"创建会话失败: {body}")
    return str(session_id)


def chat(client: httpx.Client, token: str, session_id: str, content: str) -> dict[str, list[dict]]:
    events: dict[str, list[dict]] = {}
    payload = {
        "clientMessageId": f"eval-{uuid.uuid4().hex}",
        "content": content,
        "contentType": "TEXT",
    }
    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": "text/event-stream",
        "Content-Type": "application/json",
    }
    event_name = ""
    data_lines: list[str] = []
    with client.stream("POST", f"{BASE}/api/v1/chat/sessions/{session_id}/messages",
                       headers=headers, json=payload, timeout=120.0) as response:
        response.raise_for_status()
        for line in response.iter_lines():
            if line.startswith("event:"):
                event_name = line[6:].strip()
            elif line.startswith("data:"):
                data_lines.append(line[5:].strip())
            elif line.strip() == "":
                if event_name and data_lines:
                    try:
                        data = json.loads("\n".join(data_lines))
                    except json.JSONDecodeError:
                        data = {"raw": "\n".join(data_lines)}
                    events.setdefault(event_name, []).append(data)
                event_name, data_lines = "", []
    return events


def summarize(events: dict[str, list[dict]], elapsed_ms: int) -> dict:
    triage = (events.get("triage") or [{}])[0]
    tools = []
    for item in events.get("tool_call") or []:
        tools.append({
            "name": item.get("name"),
            "status": item.get("status"),
            "error": item.get("error_code"),
            "confirm": item.get("requires_confirm"),
            "summary": item.get("result_summary"),
        })
    steps = [{
        "tool": item.get("tool"),
        "status": item.get("status"),
        "attempts": item.get("attempts"),
        "ms": item.get("duration_ms"),
        "error": item.get("error_code"),
    } for item in events.get("step") or []]
    citations = [{
        "title": item.get("title"),
        "score": item.get("score"),
        "rank": item.get("rank"),
    } for item in events.get("citation") or []]
    answer = "".join(item.get("content") or "" for item in events.get("delta") or [])
    done = (events.get("done") or [{}])[0]
    return {
        "intent": triage.get("intent"),
        "confidence": triage.get("confidence"),
        "triage_action": triage.get("action"),
        "low_confidence": triage.get("lowConfidence"),
        "degraded": triage.get("degraded"),
        "tools": tools,
        "steps": steps,
        "plans": events.get("plan") or [],
        "citations": citations,
        "ticket_card_data": events.get("ticket_card") or [],
        "export_card_data": events.get("export_card") or [],
        "ticket_cards": len(events.get("ticket_card") or []),
        "export_cards": len(events.get("export_card") or []),
        "errors": events.get("error") or [],
        "answer": answer[:1500],
        "latency_ms": done.get("latencyMs") or done.get("latency_ms") or elapsed_ms,
        "events": sorted(events),
    }


def run_case(client: httpx.Client, token: str, case: dict) -> list[dict]:
    session_id = create_session(client, token)
    turns = [case["q"], *case.get("followups", [])]
    results = []
    for index, question in enumerate(turns):
        started = time.perf_counter()
        try:
            events = chat(client, token, session_id, question)
            elapsed = int((time.perf_counter() - started) * 1000)
            record = summarize(events, elapsed)
            error = None
        except Exception as exc:  # noqa: BLE001
            elapsed = int((time.perf_counter() - started) * 1000)
            record = {"answer": "", "latency_ms": elapsed, "events": []}
            error = str(exc)
        results.append({
            "id": case["id"] if index == 0 else f"{case['id']}-followup-{index}",
            "category": case["category"],
            "question": question,
            "error": error,
            **record,
        })
    return results


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--category", default="1,2,3,4,5,6,7")
    parser.add_argument("--out", default="docs/reports/agent_eval_20261119.jsonl")
    parser.add_argument("--user", default="admin")
    args = parser.parse_args()
    categories = {int(item) for item in args.category.split(",") if item.strip()}

    out_path = ROOT / args.out
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with httpx.Client(timeout=30.0) as client:
        token = login(client, args.user)
        if not token:
            print(f"login failed: {args.user}")
            return 1
        cases = [case for case in CASES if case["category"] in categories]
        with out_path.open("a", encoding="utf-8") as out:
            for case in cases:
                for record in run_case(client, token, case):
                    out.write(json.dumps(record, ensure_ascii=False) + "\n")
                    out.flush()
                    tools = ",".join(item["name"] or "?" for item in record.get("tools") or []) or "-"
                    cites = len(record.get("citations") or [])
                    cards = (record.get("ticket_cards") or 0) + (record.get("export_cards") or 0)
                    status = "ERR" if record.get("error") or record.get("errors") else "OK"
                    print(f"{status} {record['id']} tools={tools} cites={cites} cards={cards} "
                          f"intent={record.get('intent')} {record.get('latency_ms')}ms")
    print(f"results -> {out_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
