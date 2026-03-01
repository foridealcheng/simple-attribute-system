# Fluss 本地部署方案

**问题**: Fluss Docker 镜像不可用（项目还在孵化阶段）

**解决方案**: 使用预配置的 Docker 镜像或直接运行在容器中

---

## 方案 1: 使用社区维护的镜像（推荐）

由于 Fluss 还在 Apache 孵化阶段，官方 Docker 镜像可能不可用。我们可以：

### Option A: 使用已有的 Fluss 容器（如果有）

```bash
# 检查是否有 Fluss 相关的容器
docker images | grep fluss
```

### Option B: 手动构建 Fluss 镜像

```bash
# 1. 克隆 Fluss 源码
git clone https://github.com/apache/fluss.git
cd fluss

# 2. 构建 Docker 镜像
docker build -t apache/fluss:0.8.0 .

# 3. 启动容器
docker-compose -f docker/docker-compose.yml up -d
```

---

## 方案 2: 使用 Fluss 的快速启动包（推荐用于测试）⭐

Fluss 提供了快速启动包，可以直接运行：

```bash
# 1. 下载 Fluss
cd /tmp
curl -LO https://dlcdn.apache.org/fluss/fluss-0.8.0/fluss-0.8.0-bin.tar.gz
tar -xzf fluss-0.8.0-bin.tar.gz
cd fluss-0.8.0

# 2. 启动 Coordinator
./bin/start-coordinator.sh

# 3. 启动 Server
./bin/start-server.sh

# 4. 创建 KV Table
./bin/fluss table create \
  --bootstrap-servers localhost:9110 \
  --database attribution \
  --table attribution-clicks \
  --schema "user_id STRING PRIMARY KEY, clicks_data STRING" \
  --table-type kv
```

---

## 方案 3: 临时方案 - 使用 Redis 作为替代（立即可用）⭐⭐

在 Fluss 部署好之前，可以先用 Redis 作为共享 KV 存储：

```bash
# 1. 启动 Redis
docker run -d --name redis -p 6379:6379 redis:latest

# 2. 修改代码使用 RedisKVClient（需要实现）
# 3. 测试
# 4. 等 Fluss 部署好后切换回 Fluss
```

---

## 方案 4: 继续测试 LocalTestJob（当前可用）

如果只是想验证功能，可以先用 LocalTestJob：

```bash
docker exec flink-jobmanager flink run \
  -c com.attribution.job.LocalTestJob \
  -d \
  /opt/simple-attribute-system-2.1.0.jar
```

**注意**: 这只是临时方案，生产环境必须使用 Fluss 集群。

---

## 🎯 建议的行动计划

### 立即执行（今天）

1. **尝试方案 2** - 下载 Fluss 快速启动包
2. **如果失败** - 使用方案 3（Redis 临时方案）
3. **同时** - 可以用 LocalTestJob 验证基本功能

### 本周内

4. **部署 Fluss 集群** - 使用方案 1 或 2
5. **创建 KV Table** - attribution-clicks
6. **完整测试** - 使用标准架构

### 下周

7. **性能测试** - 对比本地缓存 vs Fluss
8. **文档更新** - 部署指南、运维手册

---

## 📝 Fluss 快速启动脚本

创建 `scripts/setup-fluss.sh`:

```bash
#!/bin/bash

set -e

echo "========================================="
echo "Fluss 快速部署脚本"
echo "========================================="

FLUSS_VERSION="0.8.0"
FLUSS_HOME="/tmp/fluss-$FLUSS_VERSION"

# 1. 检查是否已安装
if [ -d "$FLUSS_HOME" ]; then
    echo "Fluss already installed at $FLUSS_HOME"
else
    # 2. 下载 Fluss
    echo "Downloading Fluss $FLUSS_VERSION..."
    cd /tmp
    curl -LO "https://dlcdn.apache.org/fluss/fluss-$FLUSS_VERSION/fluss-$FLUSS_VERSION-bin.tar.gz"
    
    # 3. 解压
    echo "Extracting..."
    tar -xzf "fluss-$FLUSS_VERSION-bin.tar.gz"
fi

# 4. 启动 Coordinator
echo "Starting Fluss Coordinator..."
cd "$FLUSS_HOME"
./bin/start-coordinator.sh &

sleep 5

# 5. 启动 Server
echo "Starting Fluss Server..."
./bin/start-server.sh &

sleep 10

# 6. 创建 KV Table
echo "Creating KV Table: attribution-clicks..."
./bin/fluss table create \
  --bootstrap-servers localhost:9110 \
  --database attribution \
  --table attribution-clicks \
  --schema "user_id STRING PRIMARY KEY, clicks_data STRING" \
  --table-type kv

echo ""
echo "========================================="
echo "✅ Fluss 部署完成！"
echo "========================================="
echo "Coordinator Web UI: http://localhost:9111"
echo "Bootstrap Servers: localhost:9110"
echo ""
```

---

## ⚠️ 当前建议

**由于 Fluss 部署可能需要时间，建议：**

1. **先用 LocalTestJob 验证功能**（5 分钟）
2. **同时部署 Fluss**（30 分钟）
3. **Fluss 部署好后，用标准架构测试**

这样不浪费时间，也能保证测试环境的准确性。

---

**您想：**
1. 立即部署 Fluss（我帮您执行脚本）
2. 先用 LocalTestJob 测试功能
3. 使用 Redis 作为临时替代

请告诉我您的选择！
