#!/bin/bash

# Retry Consumer App 停止脚本

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
LOG_DIR="$PROJECT_DIR/logs"
PID_FILE="$LOG_DIR/retry-consumer.pid"

echo "Stopping Retry Consumer App..."

if [ -f "$PID_FILE" ]; then
    PID=$(cat "$PID_FILE")
    if kill -0 "$PID" 2>/dev/null; then
        kill "$PID"
        echo "Retry Consumer App stopped (PID: $PID)"
        rm -f "$PID_FILE"
    else
        echo "Process not running (PID: $PID)"
        rm -f "$PID_FILE"
    fi
else
    echo "PID file not found. Trying to find process..."
    PID=$(pgrep -f "RocketMQRetryConsumerApplication" || true)
    if [ -n "$PID" ]; then
        kill "$PID"
        echo "Retry Consumer App stopped (PID: $PID)"
    else
        echo "No running Retry Consumer App found"
    fi
fi
