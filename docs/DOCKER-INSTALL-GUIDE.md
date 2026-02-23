# Docker Desktop 安装指南

**系统**: macOS 26.2 (Intel x86_64)

---

## 📥 安装方法

### 方法 1: 官网下载 (推荐)

1. **访问官网**
   ```
   https://www.docker.com/products/docker-desktop/
   ```

2. **下载 macOS Intel 版本**
   - 选择 "Docker Desktop for Mac (Intel)"
   - 下载 `.dmg` 文件

3. **安装**
   ```bash
   # 打开下载的 DMG 文件
   open ~/Downloads/Docker.dmg
   
   # 拖拽 Docker 到 Applications 文件夹
   ```

4. **启动 Docker Desktop**
   ```bash
   open -a Docker
   ```

5. **等待 Docker 启动完成**
   - 状态栏会出现 Docker 图标
   - 显示 "Engine running" 表示就绪

---

### 方法 2: 使用 Homebrew (推荐开发者)

```bash
# 安装 Docker Desktop
brew install --cask docker

# 启动 Docker
open -a Docker
```

---

## ✅ 验证安装

```bash
# 检查 Docker 版本
docker --version

# 检查 Docker Compose 版本
docker-compose --version

# 测试 Docker 运行
docker run hello-world
```

**预期输出**:
```
Docker version 24.x.x, build xxxxxxx
Docker Compose version v2.x.x
Hello from Docker!
```

---

## ⚙️ Docker Desktop 配置

### 推荐配置

打开 Docker Desktop → Settings → Resources:

- **CPUs**: 4 (或更多)
- **Memory**: 4GB (或更多)
- **Swap**: 1GB
- **Disk image size**: 64GB

### 启用 Kubernetes (可选)

如果需要本地 K8s 测试：
- Settings → Kubernetes → Enable Kubernetes

---

## 🐛 常见问题

### Q1: Docker Desktop 启动失败

**解决方案**:
```bash
# 重置 Docker
rm -rf ~/Library/Containers/com.docker.docker

# 重启 Docker
open -a Docker
```

### Q2: 权限问题

**解决方案**:
```bash
# 添加用户到 docker 组
sudo usermod -aG docker $USER

# 重启终端
```

### Q3: 网络问题

**解决方案**:
```bash
# 重置网络
Docker Desktop → Settings → Reset → Reset network
```

---

## 📝 安装后步骤

安装完成后，重新运行启动脚本：

```bash
cd /Users/ideal/.openclaw/workspace/SimpleAttributeSystem
./scripts/start-local.sh
```

---

## 🚀 替代方案

如果不想安装 Docker Desktop，可以：

### 方案 A: 使用 OrbStack (轻量级)

```bash
brew install orbstack
orbstack up
```

### 方案 B: 使用 Colima (开源)

```bash
brew install colima
colima start
```

### 方案 C: 使用远程 Docker

如果有远程服务器：
```bash
export DOCKER_HOST=ssh://user@remote-server
```

---

## 📞 需要帮助？

- **Docker 官方文档**: https://docs.docker.com/desktop/
- **Docker 社区**: https://forums.docker.com/
- **本项目文档**: `docker/README.md`

---

**更新时间**: 2026-02-23
