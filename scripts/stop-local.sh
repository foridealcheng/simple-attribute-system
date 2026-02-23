#!/bin/bash

# 归因系统本地环境停止脚本

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
DOCKER_DIR="$PROJECT_DIR/docker"

echo "========================================="
echo "  归因系统 - 停止本地环境"
echo "========================================="
echo ""

cd "$DOCKER_DIR"

echo "📦 停止所有服务..."
docker-compose down

echo ""
echo "✅ 服务已停止"
echo ""
echo "💡 提示:"
echo "   启动服务：./scripts/start-local.sh"
echo "   查看日志：docker-compose logs"
echo ""
