# Code Review & 发布流程

**版本**: v1.0.0  
**日期**: 2026-02-23  
**状态**: ⏳ 待推送

---

## 📋 CR 前检查清单

### 代码质量
- [x] 代码编译通过
- [x] 单元测试 12/12 通过
- [x] 端到端测试验证通过
- [x] 代码格式化
- [x] 无编译警告

### 文档完整性
- [x] README.md
- [x] RELEASE-NOTES.md
- [x] DEPLOYMENT-CHECKLIST.md
- [x] DATA-VIEW-GUIDE.md
- [x] 部署脚本

### 版本信息
- [x] 版本号：1.0.0 (已更新)
- [x] Git Tag: v1.0.0 (已创建)
- [x] 提交信息规范

---

## 🚀 推送代码到远程仓库

### 方式 1: 使用 Git 命令 (需要认证)

```bash
cd /Users/ideal/.openclaw/workspace/SimpleAttributeSystem

# 推送代码和标签
git push -u origin main --tags

# 如果提示认证，输入 GitHub Token
# Token 获取：https://github.com/settings/tokens
```

### 方式 2: 使用 GitHub CLI

```bash
# 1. 登录 GitHub (首次需要)
gh auth login
# 选择 GitHub.com → HTTPS → 登录浏览器

# 2. 推送代码
git push -u origin main --tags

# 3. 创建 Pull Request
gh pr create \
  --title "release: v1.0.0 Production Release - 归因系统正式发布" \
  --body "发布归因系统 v1.0.0 首个生产版本" \
  --base main \
  --label "release"

# 4. 查看 PR 状态
gh pr list

# 5. 查看 PR 详情
gh pr view
```

---

## 👀 Code Review 流程

### 1. 创建 PR 后

**PR 标题**: `release: v1.0.0 Production Release - 归因系统正式发布`

**PR 描述**:
```markdown
## 🎉 版本概述

Simple Attribute System v1.0.0 - 首个生产版本发布

## 核心功能

- ✅ 4 种归因模型 (Last Click, Linear, Time Decay, Position Based)
- ✅ Kafka 数据源适配
- ✅ JSON/Protobuf/Avro 格式解码
- ✅ Flink 流处理作业
- ✅ MapState 状态管理
- ✅ 灵活结果输出

## 技术栈

- Apache Flink 1.17.1
- Apache Kafka 3.4.0
- Java 11
- Maven 3.8+

## 测试验证

- 单元测试：12/12 通过
- 端到端测试：通过
- 本地环境部署：成功
- Flink 作业运行：稳定 (>5 分钟)

## 代码统计

- 37 个 Java 源文件
- ~9600 行代码
- 3 个测试类
- 6 篇文档

## 已知限制

- Fluss 集成暂不支持 (计划 v2.0)
- 去重逻辑简化版本
- 配置硬编码

## 变更日志

详见 RELEASE-NOTES.md
```

### 2. Reviewer 检查项

**代码质量**:
- [ ] 代码结构清晰
- [ ] 命名规范
- [ ] 注释充分
- [ ] 无冗余代码

**功能完整性**:
- [ ] 4 种归因模型正确
- [ ] 数据接入正常
- [ ] 状态管理正确
- [ ] 结果输出正常

**测试覆盖**:
- [ ] 单元测试充分
- [ ] 端到端测试通过
- [ ] 性能测试合格

**文档完整性**:
- [ ] README 完整
- [ ] 部署文档清晰
- [ ] API 文档完整

### 3. 解决 Review 意见

```bash
# 根据 review 意见修改代码
# ... 修改 ...

# 提交修改
git add -A
git commit -m "fix: 解决 CR 意见 - <具体修改内容>"
git push origin main

# PR 会自动更新
```

---

## ✅ CR 通过后合并

### 使用 GitHub CLI

```bash
# 1. 确认 PR 状态
gh pr view

# 2. 合并 PR (Squash Merge)
gh pr merge --squash --delete-branch

# 或者 (Merge Commit)
gh pr merge --merge --delete-branch

# 3. 切换到 master 分支
git checkout master
git pull origin master

# 4. 删除开发分支 (可选)
git branch -d release-v1.0.0
git push origin --delete release-v1.0.0
```

### 使用 GitHub Web UI

1. 访问 PR 页面
2. 点击 "Merge pull request"
3. 选择合并方式:
   - **Squash and merge** (推荐)
   - Merge commit
   - Rebase and merge
4. 确认合并
5. 删除分支

---

## 🏷️ 发布 Release

### 创建 GitHub Release

```bash
# 使用 GitHub CLI
gh release create v1.0.0 \
  --title "v1.0.0 - Production Release" \
  --notes-file RELEASE-NOTES.md \
  --generate-notes

# 或者手动创建
# 访问：https://github.com/foridealcheng/simple-attribute-system/releases/new
# Tag: v1.0.0
# Title: v1.0.0 - Production Release
```

### 上传编译产物

```bash
# 编译
mvn clean package -DskipTests

# 上传 JAR 到 Release
gh release upload v1.0.0 \
  target/simple-attribute-system-1.0.0.jar
```

---

## 📊 CR 状态跟踪

| 阶段 | 状态 | 说明 |
|------|------|------|
| 代码推送 | ⏳ 待执行 | 需要认证 |
| PR 创建 | ⏳ 待执行 | 等待推送 |
| Code Review | ⏳ 待开始 | 等待 PR |
| 修改意见 | ⏳ 未开始 | 等待 Review |
| 合并 | ⏳ 未开始 | 等待 CR 通过 |
| Release | ⏳ 未开始 | 等待合并 |

---

## 🔧 常见问题

### Q1: Git 推送失败

**错误**: `could not read Password`

**解决方案**:
```bash
# 配置凭证存储
git config --global credential.helper store

# 或者使用 Token
git push https://<TOKEN>@github.com/foridealcheng/simple-attribute-system.git main
```

### Q2: gh 认证失败

**错误**: `You are not logged into any GitHub hosts`

**解决方案**:
```bash
# 重新认证
gh auth logout
gh auth login

# 选择:
# - GitHub.com
# - HTTPS
# - Login with a browser
```

### Q3: PR 创建失败

**错误**: `no commits between main and main`

**解决方案**:
```bash
# 确认有未推送的提交
git log origin/main..main

# 如果有输出，说明有未推送的提交
# 推送后再创建 PR
git push -u origin main
gh pr create
```

---

## 📞 快速命令汇总

```bash
# 推送代码
git push -u origin main --tags

# 创建 PR
gh pr create --title "release: v1.0.0" --body "发布说明"

# 查看 PR
gh pr list
gh pr view

# 合并 PR
gh pr merge --squash --delete-branch

# 创建 Release
gh release create v1.0.0 --generate-notes

# 上传产物
gh release upload v1.0.0 target/simple-attribute-system-1.0.0.jar
```

---

**下一步**: 请执行推送命令开始 CR 流程

```bash
git push -u origin main --tags
```

---

**更新时间**: 2026-02-23 17:35
