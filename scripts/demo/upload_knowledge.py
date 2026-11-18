#!/usr/bin/env python3
"""上传演示知识文档（幂等：按标题跳过已存在文档）。

运行：
    apps/python-ai/.venv/bin/python scripts/demo/upload_knowledge.py
"""

from __future__ import annotations

import sys
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parents[2]
BASE = "http://127.0.0.1:8200/api/v1"
DOCS = [
    ("消防水泵巡检与故障处理.md", "消防水泵巡检与故障处理", "PUMP"),
    ("门禁道闸故障处置.md", "门禁道闸故障处置", "ACCESS"),
    ("空调不制冷排查.md", "空调不制冷排查", "HVAC"),
]


def load_env() -> dict[str, str]:
    values: dict[str, str] = {}
    for line in (ROOT / ".env").read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def main() -> int:
    env = load_env()
    username = env.get("DEV_ADMIN_USERNAME", "admin")
    password = env.get("DEV_ADMIN_PASSWORD", "")
    if not password:
        print("缺少 DEV_ADMIN_PASSWORD", file=sys.stderr)
        return 1

    with httpx.Client(timeout=60.0) as client:
        login = client.post(f"{BASE}/auth/login",
                            json={"username": username, "password": password})
        body = login.json()
        token = (body.get("data") or {}).get("accessToken")
        if not token:
            print(f"登录失败: {body.get('message')}", file=sys.stderr)
            return 1
        headers = {"Authorization": f"Bearer {token}"}

        existing = client.get(f"{BASE}/knowledge/docs",
                              headers=headers, params={"page": 1, "pageSize": 100}).json()
        titles = {item.get("title") for item in (existing.get("data") or {}).get("items", [])}

        for filename, title, category in DOCS:
            if title in titles:
                print(f"SKIP  {title}（已存在）")
                continue
            path = ROOT / "docs" / "demo" / "knowledge" / filename
            if not path.exists():
                print(f"MISS  {path}")
                continue
            with path.open("rb") as handle:
                response = client.post(
                    f"{BASE}/knowledge/docs",
                    headers=headers,
                    data={"title": title, "categoryCode": category},
                    files={"file": (filename, handle, "text/markdown")},
                )
            body = response.json()
            if response.status_code == 200 and body.get("code") == 0:
                data = body.get("data") or {}
                print(f"OK    {title} doc={data.get('docNo')} chunks={data.get('chunkCount')}")
            else:
                print(f"FAIL  {title} http={response.status_code} message={body.get('message')}")
                return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
