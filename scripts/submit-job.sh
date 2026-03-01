#!/bin/bash

# 提交 Flink 作业脚本

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)")
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
FLINK_JOBMANAGER="localhost:8081"

echo "========================================="
echo "  提交 Flink 归因作业"
echo "========================================="
echo ""

# 检查是否已编译
if [ ! -f "$PROJECT_DIR/target/simple-attribute-system-1.0.0-SNAPSHOT.jar" ]; then
    echo "❌ JAR 文件不存在，请先编译项目"
    echo "   cd $PROJECT_DIR && mvn clean package -DskipTests"
    exit 1
fi

echo "✅ JAR 文件已找到"
echo ""

# 检查 Flink 是否运行
if ! curl -s --connect-timeout 5 "http://$FLINK_JOBMANAGER" > /dev/null 2>&1; then
    echo "❌ Flink JobManager 未运行"
    echo "   ./scripts/start-local.sh"
    exit 1
fi

echo "✅ Flink JobManager 连接成功：http://$FLINK_JOBMANAGER"
echo ""

# 提交作业
echo "🚀 提交作业..."

docker exec flink-jobmanager flink run \
    -d \
    -c com.attribution.AttributionJob \
    -yid attribution-job \
    /opt/flink/usrlib/simple-attribute-system-1.0.0-SNAPSHOT.jar

echo ""
echo "✅ 作业提交成功"
echo ""
echo "📊 查看作业状态:"
echo "   http://$FLINK_JOBMANAGER"
echo ""
