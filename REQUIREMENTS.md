# 环境要求

> 运行 SimpleAttributeSystem 所需的环境配置

## ⚠️ 重要提示

**本项目需要 Java 11 或更高版本**

当前系统检测到的 Java 版本：Java 8 (需要升级)

---

## 📋 必需环境

### Java JDK

**要求**: Java 11 或 Java 17

```bash
# 检查当前 Java 版本
java -version

# 应该看到类似输出：
# openjdk version "11.0.x" 或 "17.0.x"
```

### 安装 Java 11 (macOS)

```bash
# 使用 Homebrew 安装
brew install openjdk@11

# 安装完成后，设置 JAVA_HOME
export JAVA_HOME=/usr/local/opt/openjdk@11
export PATH=$JAVA_HOME/bin:$PATH

# 验证安装
java -version
```

### 安装 Java 11 (Ubuntu/Debian)

```bash
sudo apt update
sudo apt install openjdk-11-jdk

# 验证安装
java -version
```

### 安装 Java 11 (CentOS/RHEL)

```bash
sudo yum install java-11-openjdk-devel

# 验证安装
java -version
```

---

## 📋 推荐环境

### Apache Maven

**要求**: Maven 3.8+

```bash
# 检查 Maven 版本
mvn -version

# macOS 安装
brew install maven

# Ubuntu/Debian 安装
sudo apt install maven
```

### Docker & Docker Compose (本地开发)

**要求**: Docker 20.10+, Docker Compose 2.0+

```bash
# 检查 Docker 版本
docker --version
docker-compose --version

# macOS: 安装 Docker Desktop
# https://www.docker.com/products/docker-desktop

# Ubuntu 安装
sudo apt install docker.io docker-compose
```

---

## 🔧 配置 JAVA_HOME

### macOS

在 `~/.zshrc` 或 `~/.bash_profile` 中添加：

```bash
# Java 11
export JAVA_HOME=/usr/local/opt/openjdk@11
export PATH=$JAVA_HOME/bin:$PATH

# 或者使用 /usr/libexec/java_home (如果有多个 Java 版本)
export JAVA_HOME=$(/usr/libexec/java_home -v 11)
```

### Linux

在 `~/.bashrc` 或 `~/.profile` 中添加：

```bash
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH
```

---

## ✅ 验证环境

运行以下命令验证所有环境：

```bash
# 检查 Java
java -version
# 应该显示 Java 11 或 17

# 检查 Maven
mvn -version
# 应该显示 Maven 3.8+

# 检查 Docker (可选)
docker --version
docker-compose --version
```

---

## 🚀 快速开始

环境配置完成后：

```bash
cd SimpleAttributeSystem

# 编译项目
mvn clean compile

# 运行测试
mvn test

# 打包（跳过测试）
mvn clean package -DskipTests
```

---

## 📝 常见问题

### Q: 为什么需要 Java 11？

A: Apache Flink 1.18+ 不再支持 Java 8，最低要求 Java 11。

### Q: 可以使用 Java 17 吗？

A: 可以！Java 17 是 LTS 版本，完全兼容。

### Q: 安装 Java 11 后 Maven 仍然报错？

A: 确保 JAVA_HOME 环境变量正确设置，并重启终端。

---

**更新时间**: 2026-02-23
