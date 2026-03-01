#!/bin/bash

# Click Writer Job 启动脚本
# v2.1.0

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
JAR_FILE="$PROJECT_DIR/target/simple-attribute-system-2.1.0.jar"
CONFIG_FILE="$PROJECT_DIR/config/click-writer.yaml"

echo "========================================="
echo "Starting Click Writer Job"
echo "========================================="
echo "Project Dir: $PROJECT_DIR"
echo "JAR File: $JAR_FILE"
echo "Config File: $CONFIG_FILE"
echo ""

# 检查 JAR 文件是否存在
if [ ! -f "$JAR_FILE" ]; then
    echo "ERROR: JAR file not found: $JAR_FILE"
    echo "Please run 'mvn clean package -DskipTests' first"
    exit 1
fi

# 设置环境变量（可选）
export KAFKA_BOOTSTRAP_SERVERS=${KAFKA_BOOTSTRAP_SERVERS:-kafka:29092}
export FLUSS_BOOTSTRAP_SERVERS=${FLUSS_BOOTSTRAP_SERVERS:-fluss:9110}

# 提交 Flink Job
echo "Submitting Click Writer Job to Flink..."
flink run \
    -c com.attribution.job.ClickWriterJob \
    -d \
    -p 4 \
    -ynm click-writer-job-v2.1 \
    "$JAR_FILE" \
    --config "$CONFIG_FILE"

echo ""
echo "========================================="
echo "Click Writer Job submitted successfully!"
echo "========================================="
echo ""
echo "To check job status:"
echo "  flink list --running"
echo ""
echo "To stop the job:"
echo "  flink cancel <job-id>"
echo ""
