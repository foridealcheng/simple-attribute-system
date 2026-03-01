#!/bin/bash

# 本地测试环境快速启动脚本
# v2.1

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

echo "========================================="
echo "本地测试环境快速启动"
echo "========================================="
echo ""

# 1. 检查 Docker
echo "Checking Docker..."
if ! docker ps >/dev/null 2>&1; then
    echo "ERROR: Docker is not running"
    exit 1
fi
echo "✓ Docker is running"
echo ""

# 2. 启动 Flink TaskManager
echo "Starting Flink TaskManager..."
if docker ps | grep -q flink-taskmanager; then
    echo "TaskManager already running"
else
    docker run -d --name flink-taskmanager \
      --link flink-jobmanager:jobmanager \
      -e FLINK_PROPERTIES="jobmanager.rpc.address: jobmanager" \
      flink:1.17.1 taskmanager
    echo "✓ TaskManager started"
fi
echo ""

# 3. 创建 Kafka Topics
echo "Creating Kafka topics..."

# 使用正确的路径
KAFKA_CONTAINER=$(docker ps | grep confluentinc/cp-kafka | awk '{print $1}')

if [ -z "$KAFKA_CONTAINER" ]; then
    echo "ERROR: Kafka container not found"
    exit 1
fi

# 创建 Topics（如果不存在）
docker exec $KAFKA_CONTAINER kafka-topics --bootstrap-server localhost:9092 \
  --create --topic click-events --partitions 1 --replication-factor 1 2>/dev/null || echo "  click-events exists"

docker exec $KAFKA_CONTAINER kafka-topics --bootstrap-server localhost:9092 \
  --create --topic conversion-events --partitions 1 --replication-factor 1 2>/dev/null || echo "  conversion-events exists"

docker exec $KAFKA_CONTAINER kafka-topics --bootstrap-server localhost:9092 \
  --create --topic attribution-results-success --partitions 1 --replication-factor 1 2>/dev/null || echo "  attribution-results-success exists"

docker exec $KAFKA_CONTAINER kafka-topics --bootstrap-server localhost:9092 \
  --create --topic attribution-results-failed --partitions 1 --replication-factor 1 2>/dev/null || echo "  attribution-results-failed exists"

echo "✓ Kafka topics created"
echo ""

# 4. 编译项目
echo "Building project..."
cd "$PROJECT_DIR"
mvn clean package -DskipTests -q
echo "✓ Build completed"
echo ""

# 5. 复制 JAR 到 Flink 容器
echo "Copying JAR to Flink container..."
docker cp "$PROJECT_DIR/target/simple-attribute-system-2.1.0.jar" flink-jobmanager:/opt/
echo "✓ JAR copied"
echo ""

# 6. 提交 Click Writer Job
echo "Submitting Click Writer Job..."
docker exec flink-jobmanager flink run \
  -c com.attribution.job.ClickWriterJob \
  -d \
  /opt/simple-attribute-system-2.1.0.jar

echo "✓ Click Writer Job submitted"
echo ""

# 7. 提交 Attribution Engine Job
echo "Submitting Attribution Engine Job..."
docker exec flink-jobmanager flink run \
  -c com.attribution.job.AttributionEngineJob \
  -d \
  /opt/simple-attribute-system-2.1.0.jar

echo "✓ Attribution Engine Job submitted"
echo ""

# 8. 启动 Retry Consumer（本地运行）
echo "Starting Retry Consumer App..."
cd "$PROJECT_DIR"
nohup java -jar target/simple-attribute-system-2.1.0.jar \
  com.attribution.consumer.RocketMQRetryConsumerApplication > logs/retry-consumer.log 2>&1 &
echo $! > logs/retry-consumer.pid
echo "✓ Retry Consumer started (PID: $(cat logs/retry-consumer.pid))"
echo ""

echo "========================================="
echo "✅ 启动完成！"
echo "========================================="
echo ""
echo "Web UIs:"
echo "  - Flink: http://localhost:8081"
echo "  - Kafka UI: http://localhost:8090"
echo "  - RocketMQ Console: http://localhost:8082"
echo ""
echo "To send test data:"
echo "  # Click event"
echo "  docker exec $KAFKA_CONTAINER kafka-console-producer --bootstrap-server localhost:9092 --topic click-events"
echo '  {"eventId":"click-001","userId":"user-001","timestamp":1708512000000,"advertiserId":"adv-001"}'
echo ""
echo "  # Conversion event"
echo "  docker exec $KAFKA_CONTAINER kafka-console-producer --bootstrap-server localhost:9092 --topic conversion-events"
echo '  {"eventId":"conv-001","userId":"user-001","timestamp":1708515600000,"advertiserId":"adv-001","conversionType":"PURCHASE","conversionValue":299.99}'
echo ""
echo "To check logs:"
echo "  tail -f logs/retry-consumer.log"
echo ""
echo "To stop:"
echo "  $SCRIPT_DIR/stop-all.sh"
echo ""
