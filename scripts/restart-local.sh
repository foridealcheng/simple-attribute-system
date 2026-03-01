#!/bin/bash

# 归因系统本地环境重启脚本

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

echo "🔄 重启本地环境..."
echo ""

# 停止
"$SCRIPT_DIR/stop-local.sh"

# 等待
echo "⏳ 等待 5 秒..."
sleep 5

# 启动
"$SCRIPT_DIR/start-local.sh"
