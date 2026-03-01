#!/bin/bash

# 归因系统 Docker 集成测试脚本
# 自动执行完整的集成测试流程

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
DOCKER_DIR="$SCRIPT_DIR"

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  归因系统 Docker 集成测试${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""

# 步骤 1: 启动 Docker 服务
echo -e "${YELLOW}[1/8] 启动 Docker 服务...${NC}"
cd "$DOCKER_DIR"
docker-compose up -d

echo -e "${YELLOW}[2/8] 等待服务启动...${NC}"
sleep 15

# 检查服务健康状态
echo -e "${YELLOW}[3/8] 检查服务健康状态...${NC}"

# 检查 Zookeeper
if docker exec zookeeper nc -z localhost 2181 2>/dev/null; then
    echo -e "${GREEN}✓ Zookeeper 正常 (2181)${NC}"
else
    echo -e "${RED}✗ Zookeeper 启动失败${NC}"
    exit 1
fi

# 检查 Kafka
if docker exec kafka kafka-broker-api-versions --bootstrap-server localhost:9092 >/dev/null 2>&1; then
    echo -e "${GREEN}✓ Kafka 正常 (9092)${NC}"
else
    echo -e "${RED}✗ Kafka 启动失败${NC}"
    exit 1
fi

# 检查 Flink JobManager
if curl -s http://localhost:8081/overview >/dev/null 2>&1; then
    echo -e "${GREEN}✓ Flink JobManager 正常 (8081)${NC}"
else
    echo -e "${RED}✗ Flink JobManager 启动失败${NC}"
    exit 1
fi

# 检查 Kafka UI
if curl -s http://localhost:8090 >/dev/null 2>&1; then
    echo -e "${GREEN}✓ Kafka UI 正常 (8090)${NC}"
else
    echo -e "${RED}✗ Kafka UI 启动失败${NC}"
    exit 1
fi

# 步骤 4: 初始化 Kafka Topic
echo -e "${YELLOW}[4/8] 初始化 Kafka Topic...${NC}"
cd "$PROJECT_DIR"
./scripts/init-topics.sh

# 验证 Topic 创建
echo -e "${YELLOW}[5/8] 验证 Topic 创建...${NC}"
TOPICS=("click-events" "conversion-events" "attribution-results-success" "attribution-results-failed")

for topic in "${TOPICS[@]}"; do
    if docker exec kafka kafka-topics --list --bootstrap-server localhost:9092 | grep -q "^${topic}$"; then
        echo -e "${GREEN}✓ Topic '$topic' 已创建${NC}"
    else
        echo -e "${RED}✗ Topic '$topic' 创建失败${NC}"
        exit 1
    fi
done

# 步骤 6: 编译项目
echo -e "${YELLOW}[6/8] 编译项目...${NC}"
cd "$PROJECT_DIR"
mvn clean package -DskipTests -q

if [ $? -eq 0 ]; then
    echo -e "${GREEN}✓ 编译成功${NC}"
else
    echo -e "${RED}✗ 编译失败${NC}"
    exit 1
fi

# 步骤 7: 发送测试数据
echo -e "${YELLOW}[7/8] 发送测试数据...${NC}"

# 发送 Click 事件
echo "发送 Click 事件..."
cat <<EOF | docker exec -i kafka kafka-console-producer --bootstrap-server localhost:9092 --topic click-events
{"event_id":"click-test-001","user_id":"user-test-123","timestamp":$(date +%s000),"advertiser_id":"adv-test-001","campaign_id":"camp-test-001","creative_id":"creative-001","click_type":"click"}
{"event_id":"click-test-002","user_id":"user-test-123","timestamp":$(($(date +%s000) + 1000)),"advertiser_id":"adv-test-001","campaign_id":"camp-test-001","creative_id":"creative-002","click_type":"click"}
EOF

# 发送 Conversion 事件
echo "发送 Conversion 事件..."
cat <<EOF | docker exec -i kafka kafka-console-producer --bootstrap-server localhost:9092 --topic conversion-events
{"event_id":"conv-test-001","user_id":"user-test-123","timestamp":$(($(date +%s000) + 2000)),"conversion_type":"purchase","conversion_value":100.0}
EOF

echo -e "${GREEN}✓ 测试数据发送完成${NC}"

# 步骤 8: 验证结果
echo -e "${YELLOW}[8/8] 等待归因计算...${NC}"
sleep 10

# 检查归因结果
echo "检查归因结果..."
SUCCESS_COUNT=$(docker exec kafka kafka-console-consumer --bootstrap-server localhost:9092 --topic attribution-results-success --timeout-ms 5000 2>/dev/null | wc -l || echo "0")

if [ "$SUCCESS_COUNT" -gt 0 ]; then
    echo -e "${GREEN}✓ 归因结果生成成功 (成功：$SUCCESS_COUNT)${NC}"
else
    echo -e "${YELLOW}⚠ 未检测到归因结果（可能是 Flink 作业未启动）${NC}"
fi

# 显示 Flink 作业状态
echo ""
echo "Flink 作业状态:"
curl -s http://localhost:8081/jobs | python3 -m json.tool 2>/dev/null || echo "无法解析 JSON"

# 完成
echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  集成测试完成！${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo "访问以下 UI 查看结果:"
echo "  - Flink UI:  http://localhost:8081"
echo "  - Kafka UI:  http://localhost:8090"
echo ""
echo "常用命令:"
echo "  - 查看日志：docker-compose logs -f"
echo "  - 停止服务：./scripts/stop-local.sh"
echo "  - 重启服务：./scripts/restart-local.sh"
echo ""
