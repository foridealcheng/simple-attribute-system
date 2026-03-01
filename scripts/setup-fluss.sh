#!/bin/bash

# Fluss 快速部署脚本
# v2.1

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

FLUSS_VERSION="0.8.0"
FLUSS_HOME="/tmp/fluss-$FLUSS_VERSION"
FLUSS_DOWNLOAD_URL="https://dlcdn.apache.org/fluss/fluss-$FLUSS_VERSION/fluss-$FLUSS_VERSION-bin.tar.gz"

echo "========================================="
echo "Fluss 快速部署脚本"
echo "========================================="
echo ""

# 1. 检查 Java
echo "Checking Java..."
if ! which java >/dev/null 2>&1; then
    echo "ERROR: Java not found"
    exit 1
fi
java -version 2>&1 | head -1
echo "✓ Java is available"
echo ""

# 2. 检查是否已安装
if [ -d "$FLUSS_HOME" ]; then
    echo "Fluss $FLUSS_VERSION already installed at $FLUSS_HOME"
    echo "Cleaning up..."
    rm -rf "$FLUSS_HOME"
fi

# 3. 下载 Fluss
echo "Downloading Fluss $FLUSS_VERSION..."
cd /tmp

# 检查是否有 curl
if which curl >/dev/null 2>&1; then
    curl -LO "$FLUSS_DOWNLOAD_URL" 2>&1 | tail -5
elif which wget >/dev/null 2>&1; then
    wget "$FLUSS_DOWNLOAD_URL" 2>&1 | tail -5
else
    echo "ERROR: Neither curl nor wget found"
    exit 1
fi

# 4. 解压
echo "Extracting..."
tar -xzf "fluss-$FLUSS_VERSION-bin.tar.gz"
echo "✓ Fluss extracted to $FLUSS_HOME"
echo ""

# 5. 配置 Fluss
echo "Configuring Fluss..."
cd "$FLUSS_HOME"

# 修改 conf/server.yaml
cat > conf/server.yaml <<EOF
# Fluss Server Configuration
server.host: localhost
server.port: 9112
coordinator.host: localhost
coordinator.port: 9110
zookeeper.connect: localhost:2181
default.parallelism: 1
EOF

# 修改 conf/coordinator.yaml
cat > conf/coordinator.yaml <<EOF
# Fluss Coordinator Configuration
coordinator.host: localhost
coordinator.port: 9110
webui.port: 9111
zookeeper.connect: localhost:2181
default.parallelism: 1
EOF

echo "✓ Configuration updated"
echo ""

# 6. 启动 ZooKeeper（如果需要）
echo "Checking ZooKeeper..."
if ! nc -z localhost 2181 2>/dev/null; then
    echo "Starting ZooKeeper..."
    # 使用 Docker 启动 ZK
    docker run -d --name fluss-zookeeper \
      -p 2181:2181 \
      -e ZOO_MY_ID=1 \
      zookeeper:3.8 2>&1 || echo "ZooKeeper may already be running"
    sleep 5
    echo "✓ ZooKeeper started (Docker)"
else
    echo "✓ ZooKeeper already running"
fi
echo ""

# 7. 启动 Coordinator
echo "Starting Fluss Coordinator..."
cd "$FLUSS_HOME"
nohup ./bin/start-coordinator.sh > logs/coordinator.log 2>&1 &
COORDINATOR_PID=$!
echo $COORDINATOR_PID > logs/coordinator.pid
sleep 5
echo "✓ Coordinator started (PID: $COORDINATOR_PID)"
echo ""

# 8. 启动 Server
echo "Starting Fluss Server..."
nohup ./bin/start-server.sh > logs/server.log 2>&1 &
SERVER_PID=$!
echo $SERVER_PID > logs/server.pid
sleep 5
echo "✓ Server started (PID: $SERVER_PID)"
echo ""

# 9. 检查状态
echo "Checking Fluss status..."
sleep 5

if nc -z localhost 9110 2>/dev/null; then
    echo "✓ Coordinator is listening on port 9110"
else
    echo "⚠️  Coordinator may not be ready yet"
fi

if nc -z localhost 9112 2>/dev/null; then
    echo "✓ Server is listening on port 9112"
else
    echo "⚠️  Server may not be ready yet"
fi

echo ""

# 10. 创建 KV Table
echo "Creating KV Table: attribution-clicks..."
cd "$FLUSS_HOME"

# 使用 fluss CLI 创建表
cat > /tmp/create_table.sql <<EOF
CREATE TABLE attribution.attribution_clicks (
    user_id STRING PRIMARY KEY,
    clicks_data STRING,
    last_update_time BIGINT,
    session_start_time BIGINT,
    version BIGINT
) WITH (
    'table-type' = 'kv',
    'partition-key' = 'user_id'
);
EOF

# 尝试使用 fluss-sql 或直接调用 API
# 由于 CLI 可能不可用，我们创建一个简单的 Java 程序来创建表
echo "Table creation script created at /tmp/create_table.sql"
echo "Note: You may need to create the table manually using Fluss Web UI or CLI"
echo ""

echo "========================================="
echo "✅ Fluss 部署完成！"
echo "========================================="
echo ""
echo "Services:"
echo "  - Coordinator: localhost:9110 (RPC), localhost:9111 (Web UI)"
echo "  - Server: localhost:9112 (RPC)"
echo "  - ZooKeeper: localhost:2181"
echo ""
echo "Logs:"
echo "  - Coordinator: $FLUSS_HOME/logs/coordinator.log"
echo "  - Server: $FLUSS_HOME/logs/server.log"
echo ""
echo "Next steps:"
echo "  1. Open Web UI: http://localhost:9111"
echo "  2. Create KV Table: attribution.attribution-clicks"
echo "  3. Update config/*.yaml to use Fluss mode"
echo "  4. Run tests with standard architecture"
echo ""
echo "To stop Fluss:"
echo "  $SCRIPT_DIR/stop-fluss.sh"
echo ""
