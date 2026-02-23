#!/bin/bash

# 初始化 Kafka Topic 脚本

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

KAFKA_BOOTSTRAP="localhost:9092"
ZOOKEEPER="localhost:2181"

echo "========================================="
echo "  初始化 Kafka Topic"
echo "========================================="
echo ""

# 检查 Kafka 是否运行
if ! curl -s --connect-timeout 5 "tcp://localhost:9092" > /dev/null 2>&1; then
    echo "❌ Kafka 未运行，请先启动本地环境"
    echo "   ./scripts/start-local.sh"
    exit 1
fi

echo "✅ Kafka 连接成功：$KAFKA_BOOTSTRAP"
echo ""

# 创建 Topic
echo "📝 创建 Topic..."

# Click 事件 Topic
echo "   - click-events"
docker exec kafka kafka-topics --create \
    --bootstrap-server $KAFKA_BOOTSTRAP \
    --topic click-events \
    --partitions 3 \
    --replication-factor 1 \
    --if-not-exists 2>/dev/null || echo "   Topic click-events 已存在"

# Conversion 事件 Topic
echo "   - conversion-events"
docker exec kafka kafka-topics --create \
    --bootstrap-server $KAFKA_BOOTSTRAP \
    --topic conversion-events \
    --partitions 3 \
    --replication-factor 1 \
    --if-not-exists 2>/dev/null || echo "   Topic conversion-events 已存在"

# 归因结果 Topic (成功)
echo "   - attribution-results-success"
docker exec kafka kafka-topics --create \
    --bootstrap-server $KAFKA_BOOTSTRAP \
    --topic attribution-results-success \
    --partitions 3 \
    --replication-factor 1 \
    --if-not-exists 2>/dev/null || echo "   Topic attribution-results-success 已存在"

# 归因结果 Topic (失败/重试)
echo "   - attribution-results-failed"
docker exec kafka kafka-topics --create \
    --bootstrap-server $KAFKA_BOOTSTRAP \
    --topic attribution-results-failed \
    --partitions 3 \
    --replication-factor 1 \
    --if-not-exists 2>/dev/null || echo "   Topic attribution-results-failed 已存在"

echo ""
echo "✅ Topic 创建完成"
echo ""

# 列出所有 Topic
echo "📋 当前 Topic 列表:"
docker exec kafka kafka-topics --list --bootstrap-server $KAFKA_BOOTSTRAP

echo ""
echo "========================================="
echo "  🎉 初始化完成！"
echo "========================================="
echo ""
echo "📝 测试消息:"
echo ""
echo "   发送测试 Click 事件:"
echo "   docker exec kafka kafka-console-producer \\"
echo "     --bootstrap-server $KAFKA_BOOTSTRAP \\"
echo "     --topic click-events"
echo ""
echo "   消费消息:"
echo "   docker exec kafka kafka-console-consumer \\"
echo "     --bootstrap-server $KAFKA_BOOTSTRAP \\"
echo "     --topic click-events \\"
echo "     --from-beginning"
echo ""
