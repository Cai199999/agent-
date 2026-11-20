#!/usr/bin/env bash
# 子 Agent 轻量门禁：单元/契约测试 + 可选运行时契约。
# 用法：
#   scripts/ci/verify-agent.sh
#   AGENT_BASE=http://127.0.0.1:8200 scripts/ci/verify-agent.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PY="$ROOT/apps/python-ai/.venv/bin/python"

if [[ -x "$ROOT/.jdk/bin/java" ]]; then
  export JAVA_HOME="$ROOT/.jdk"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

echo "[1/3] Java 契约单元测试"
(cd "$ROOT/apps/java-bff" && mvn -q test)

echo "[2/3] Python 拆解/重试单元测试"
(cd "$ROOT/apps/python-ai" && "$PY" -m unittest discover -s tests -v)

if [[ -n "${AGENT_BASE:-}" ]]; then
  echo "[3/3] 运行时契约测试（$AGENT_BASE）"
  "$PY" "$ROOT/scripts/e2e/agent_contract_test.py"
else
  echo "[3/3] 跳过运行时契约测试（设置 AGENT_BASE 后执行）"
fi

echo "OK: agent verification passed"
