#!/bin/bash

# 停止所有本地测试服务

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
LOG_DIR="$PROJECT_DIR/logs"

echo "Stopping local test environment..."
echo ""

# 1. 停止 Retry Consumer
if [ -f "$LOG_DIR/retry-consumer.pid" ]; then
    PID=$(cat "$LOG_DIR/retry-consumer.pid")
    if kill -0 "$PID" 2>/dev/null; then
        kill "$PID"
        echo "✓ Retry Consumer stopped (PID: $PID)"
    else
        echo "✓ Retry Consumer not running"
    fi
    rm -f "$LOG_DIR/retry-consumer.pid"
else
    echo "✓ Retry Consumer PID file not found"
fi

# 2. 停止 Flink 任务
echo "Stopping Flink jobs..."
docker exec flink-jobmanager flink list --running 2>/dev/null | \
  grep "Click Writer Job\|Attribution Engine Job" | \
  awk '{print $4}' | \
  while read job_id; do
    docker exec flink-jobmanager flink cancel "$job_id" 2>/dev/null && \
      echo "✓ Job cancelled: $job_id"
  done || echo "✓ No running jobs"

# 3. 停止 Flink TaskManager
if docker ps | grep -q flink-taskmanager; then
    docker stop flink-taskmanager >/dev/null
    docker rm flink-taskmanager >/dev/null
    echo "✓ Flink TaskManager stopped"
else
    echo "✓ Flink TaskManager not running"
fi

echo ""
echo "All services stopped."
echo ""
echo "Note: Kafka, RocketMQ, and Flink JobManager are still running."
echo "To stop them:"
echo "  docker stop kafka kafka-ui rocketmq-broker rocketmq-namesrv rocketmq-console flink-jobmanager"
echo ""
