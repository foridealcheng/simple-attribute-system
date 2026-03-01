#!/bin/bash

# 停止 Fluss 服务

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
FLUSS_HOME="/tmp/fluss-0.8.0"

echo "Stopping Fluss services..."
echo ""

# 1. 停止 Server
if [ -f "$FLUSS_HOME/logs/server.pid" ]; then
    PID=$(cat "$FLUSS_HOME/logs/server.pid")
    if kill -0 "$PID" 2>/dev/null; then
        kill "$PID"
        echo "✓ Server stopped (PID: $PID)"
    else
        echo "✓ Server not running"
    fi
    rm -f "$FLUSS_HOME/logs/server.pid"
else
    echo "✓ Server PID file not found"
fi

# 2. 停止 Coordinator
if [ -f "$FLUSS_HOME/logs/coordinator.pid" ]; then
    PID=$(cat "$FLUSS_HOME/logs/coordinator.pid")
    if kill -0 "$PID" 2>/dev/null; then
        kill "$PID"
        echo "✓ Coordinator stopped (PID: $PID)"
    else
        echo "✓ Coordinator not running"
    fi
    rm -f "$FLUSS_HOME/logs/coordinator.pid"
else
    echo "✓ Coordinator PID file not found"
fi

# 3. 停止 ZooKeeper (Docker)
if docker ps | grep -q fluss-zookeeper; then
    docker stop fluss-zookeeper >/dev/null
    docker rm fluss-zookeeper >/dev/null
    echo "✓ ZooKeeper (Docker) stopped"
else
    echo "✓ ZooKeeper not running"
fi

echo ""
echo "All Fluss services stopped."
echo ""
echo "Note: Fluss data is preserved at $FLUSS_HOME"
echo "To start again: $SCRIPT_DIR/setup-fluss.sh"
echo ""
