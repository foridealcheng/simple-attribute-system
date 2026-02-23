#!/bin/bash

# 归因系统本地环境启动脚本
# Usage: ./start-local.sh [dev|prod]

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
DOCKER_DIR="$PROJECT_DIR/docker"

echo "========================================="
echo "  归因系统 - 本地测试环境启动"
echo "========================================="
echo ""

# 检查 Docker
if ! command -v docker &> /dev/null; then
    echo "❌ Docker 未安装，请先安装 Docker Desktop"
    exit 1
fi

if ! command -v docker-compose &> /dev/null; then
    echo "❌ Docker Compose 未安装"
    exit 1
fi

echo "✅ Docker 版本：$(docker --version)"
echo "✅ Docker Compose 版本：$(docker-compose --version)"
echo ""

# 进入 docker 目录
cd "$DOCKER_DIR"

# 停止旧容器
echo "📦 停止旧容器..."
docker-compose down 2>/dev/null || true

# 启动服务
echo "🚀 启动服务..."
docker-compose up -d

# 等待服务就绪
echo ""
echo "⏳ 等待服务启动..."
sleep 10

# 检查服务状态
echo ""
echo "📊 检查服务状态..."

check_service() {
    local name=$1
    local port=$2
    local url=$3
    
    if curl -s --connect-timeout 5 "$url" > /dev/null 2>&1; then
        echo "✅ $name (端口 $port) - 就绪"
        return 0
    else
        echo "⏳ $name (端口 $port) - 启动中..."
        return 1
    fi
}

# 检查 Zookeeper
check_service "Zookeeper" "2181" "tcp://localhost:2181" || true

# 检查 Kafka
MAX_RETRIES=30
RETRY_COUNT=0
while [ $RETRY_COUNT -lt $MAX_RETRIES ]; do
    if check_service "Kafka" "9092" "tcp://localhost:9092"; then
        break
    fi
    echo "   等待 Kafka 启动... ($RETRY_COUNT/$MAX_RETRIES)"
    sleep 5
    RETRY_COUNT=$((RETRY_COUNT + 1))
done

if [ $RETRY_COUNT -eq $MAX_RETRIES ]; then
    echo "❌ Kafka 启动超时，请检查日志"
    docker-compose logs kafka
    exit 1
fi

# 检查 Flink
sleep 5
check_service "Flink JobManager" "8081" "http://localhost:8081" || true

# 检查 Kafka UI
check_service "Kafka UI" "8090" "http://localhost:8090" || true

echo ""
echo "========================================="
echo "  🎉 服务启动完成！"
echo "========================================="
echo ""
echo "📍 访问地址:"
echo "   - Flink UI:      http://localhost:8081"
echo "   - Kafka UI:      http://localhost:8090"
echo ""
echo "🔌 连接信息:"
echo "   - Kafka:         localhost:9092"
echo "   - Zookeeper:     localhost:2181"
echo ""
echo "📝 常用命令:"
echo "   查看日志：  docker-compose logs -f"
echo "   停止服务：  ./stop-local.sh"
echo "   重启服务：  ./restart-local.sh"
echo ""
echo "🚀 下一步:"
echo "   1. 创建 Kafka Topic"
echo "      ./scripts/init-topics.sh"
echo ""
echo "   2. 编译并提交 Flink 作业"
echo "      cd $PROJECT_DIR"
echo "      mvn clean package"
echo "      ./scripts/submit-job.sh"
echo ""
echo "========================================="
