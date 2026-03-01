#!/bin/bash

# Retry Consumer App 启动脚本
# v2.1.0

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
JAR_FILE="$PROJECT_DIR/target/simple-attribute-system-2.1.0.jar"
CONFIG_FILE="$PROJECT_DIR/config/retry-consumer.yaml"
LOG_DIR="$PROJECT_DIR/logs"

echo "========================================="
echo "Starting Retry Consumer App"
echo "========================================="
echo "Project Dir: $PROJECT_DIR"
echo "JAR File: $JAR_FILE"
echo "Config File: $CONFIG_FILE"
echo "Log Dir: $LOG_DIR"
echo ""

# 检查 JAR 文件是否存在
if [ ! -f "$JAR_FILE" ]; then
    echo "ERROR: JAR file not found: $JAR_FILE"
    echo "Please run 'mvn clean package -DskipTests' first"
    exit 1
fi

# 创建日志目录
mkdir -p "$LOG_DIR"

# 设置环境变量（可选）
export KAFKA_BOOTSTRAP_SERVERS=${KAFKA_BOOTSTRAP_SERVERS:-kafka:29092}
export FLUSS_BOOTSTRAP_SERVERS=${FLUSS_BOOTSTRAP_SERVERS:-fluss:9110}
export ROCKETMQ_NAMESERVER=${ROCKETMQ_NAMESERVER:-rocketmq-namesrv:9876}

# 启动 Retry Consumer
echo "Starting Retry Consumer App..."
java -jar \
    -Dlog.file="$LOG_DIR/retry-consumer.log" \
    "$JAR_FILE" \
    com.attribution.consumer.RocketMQRetryConsumerApplication \
    --config "$CONFIG_FILE" &

PID=$!
echo $PID > "$LOG_DIR/retry-consumer.pid"

echo ""
echo "========================================="
echo "Retry Consumer App started successfully!"
echo "========================================="
echo "PID: $PID"
echo ""
echo "To check logs:"
echo "  tail -f $LOG_DIR/retry-consumer.log"
echo ""
echo "To stop the app:"
echo "  kill $PID"
echo "  or: $SCRIPT_DIR/stop-retry-consumer.sh"
echo ""
