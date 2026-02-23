#!/bin/bash

# 生成测试数据脚本

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

KAFKA_BOOTSTRAP="localhost:9092"

echo "========================================="
echo "  生成测试数据"
echo "========================================="
echo ""

# 检查 Kafka 是否运行
if ! curl -s --connect-timeout 5 "tcp://localhost:9092" > /dev/null 2>&1; then
    echo "❌ Kafka 未运行"
    exit 1
fi

echo "✅ Kafka 连接成功"
echo ""

# 生成 Click 事件
echo "📊 生成 Click 事件..."

for i in {1..20}; do
    USER_ID="user-$(printf "%03d" $((RANDOM % 100 + 1)))"
    TIMESTAMP=$(($(date +%s%N)/1000000 - RANDOM * 1000))
    
    MESSAGE="{
        \"event_id\": \"click-$i-$(date +%s)\",
        \"user_id\": \"$USER_ID\",
        \"timestamp\": $TIMESTAMP,
        \"advertiser_id\": \"adv-001\",
        \"campaign_id\": \"camp-001\",
        \"creative_id\": \"cre-001\",
        \"click_type\": \"click\",
        \"device_type\": \"mobile\",
        \"os\": \"iOS\"
    }"
    
    echo "$MESSAGE" | docker exec -i kafka kafka-console-producer \
        --bootstrap-server $KAFKA_BOOTSTRAP \
        --topic click-events 2>/dev/null
    
    echo "   ✅ Click $i: user=$USER_ID"
done

echo ""
echo "✅ Click 事件生成完成 (20 条)"
echo ""

# 等待一下
sleep 2

# 生成 Conversion 事件
echo "📊 生成 Conversion 事件..."

for i in {1..10}; do
    USER_ID="user-$(printf "%03d" $((RANDOM % 100 + 1)))"
    TIMESTAMP=$(($(date +%s%N)/1000000))
    VALUE=$(echo "scale=2; $((RANDOM % 1000 + 100)) / 10" | bc)
    
    MESSAGE="{
        \"event_id\": \"conv-$i-$(date +%s)\",
        \"user_id\": \"$USER_ID\",
        \"timestamp\": $TIMESTAMP,
        \"advertiser_id\": \"adv-001\",
        \"campaign_id\": \"camp-001\",
        \"conversion_type\": \"purchase\",
        \"conversion_value\": $VALUE,
        \"currency\": \"CNY\"
    }"
    
    echo "$MESSAGE" | docker exec -i kafka kafka-console-producer \
        --bootstrap-server $KAFKA_BOOTSTRAP \
        --topic conversion-events 2>/dev/null
    
    echo "   ✅ Conversion $i: user=$USER_ID, value=$VALUE"
done

echo ""
echo "✅ Conversion 事件生成完成 (10 条)"
echo ""

echo "========================================="
echo "  🎉 测试数据生成完成！"
echo "========================================="
echo ""
echo "📊 数据统计:"
echo "   - Click 事件：20 条"
echo "   - Conversion 事件：10 条"
echo ""
echo "📝 查看消息:"
echo "   docker exec kafka kafka-console-consumer \\"
echo "     --bootstrap-server localhost:9092 \\"
echo "     --topic click-events \\"
echo "     --from-beginning"
echo ""
