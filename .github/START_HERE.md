# 🚀 Maven Central 正式版发布 - 快速导航

> 👋 欢迎！这里有完整的 Maven Central 正式版发布配置。
> 
> **✨ 新功能**: 自动版本管理！发布时自动处理版本号更新。

## 🚀 立即开始（选择你的情况）

### 情况 1: 我想立即发布（5 分钟快速开始）
👉 读: **[QUICK_START.md](./QUICK_START.md)**

### 情况 2: 我想了解工作流是怎么样的
👉 读: **[README_RELEASE.md](./README_RELEASE.md)**

### 情况 3: 我想知道完整的配置过程
👉 读: **[SETUP_SUMMARY.md](./SETUP_SUMMARY.md)**

### 情况 4: 我遇到了问题
👉 查看: **[RELEASE_CHECKLIST.md](./RELEASE_CHECKLIST.md)** 的故障排查

### 情况 5: 我想深入了解每个细节
👉 读: **[MAVEN_CENTRAL_RELEASE.md](./MAVEN_CENTRAL_RELEASE.md)**

### 情况 6: 我想验证本地配置
👉 运行:
```bash
# Linux/macOS
bash scripts/verify-release.sh

# Windows
powershell scripts/verify-release.ps1
```

---

## 🎯 工作流文件

新增文件: **[`.github/workflows/maven-release.yml`](./workflows/maven-release.yml)**

功能:
- ✅ Git tag v* 自动触发发布
- ✅ 完整的 GPG 签名流程
- ✅ 自动上传到 Maven Central
- ✅ 自动创建 GitHub Release
- ✅ **✨ 自动版本管理**:
  - 发布前: `2.21.11-SNAPSHOT` → `2.21.11`
  - 发布后: `2.21.11` → `2.21.12-SNAPSHOT`

---

## 📝 自动版本管理流程

### 发布前
```
当前版本: 2.21.11-SNAPSHOT
     ↓ (自动)
发布版本: 2.21.11
     ↓ (发布到 Maven Central)
成功发布!
     ↓ (自动)
下一个版本: 2.21.12-SNAPSHOT
```

### 工作流步骤
1. **解析版本** - 从 pom.xml 读取当前版本
2. **更新版本** - 移除 `-SNAPSHOT` 后缀
3. **提交更改** - 推送到 main 分支
4. **发布** - 带 GPG 签名发布到 Maven Central
5. **递增版本** - 自动计算下一个 SNAPSHOT 版本
6. **提交新版本** - 推送到 main 分支

---

## ⚡ 超快速 (90 秒)

### 你已经有 GPG 密钥和 Sonatype token?

```bash
# 1. 确保当前版本是 SNAPSHOT 格式
# pom.xml 中应该是: <version>2.21.11-SNAPSHOT</version>

# 2. 配置 GitHub Secrets (4 个)
# OSSRH_USERNAME, OSSRH_TOKEN, GPG_PRIVATE_KEY, GPG_PASSPHRASE

# 3. 发布版本 (自动处理版本号!)
git tag -a v2.21.11 -m "Release version 2.21.11"
git push origin v2.21.11

# 工作流会自动:
# ✓ 2.21.11-SNAPSHOT → 2.21.11 (发布)
# ✓ 发布成功后 → 2.21.12-SNAPSHOT (开发)
```

---

## 📚 文档导览

| 文档 | 内容 | 适合 | 耗时 |
|------|------|------|------|
| **QUICK_START.md** | 5 步快速开始 | 急于上手 | 5 min |
| **README_RELEASE.md** | 工作流概览 | 想了解概况 | 10 min |
| **SETUP_SUMMARY.md** | 完整配置总结 | 配置管理者 | 20 min |
| **MAVEN_CENTRAL_RELEASE.md** | 详细配置指南 | 深入了解 | 30 min |
| **RELEASE_CHECKLIST.md** | 检查清单和故障排查 | 发布时参考 | 按需 |
| **IMPLEMENTATION_REPORT.md** | 实施完成报告 | 查看成果 | 15 min |
| **verify-release.sh** | Bash 验证脚本 | Linux/macOS 用户 | 1-2 min |
| **verify-release.ps1** | PowerShell 验证脚本 | Windows 用户 | 1-2 min |

---

## 🔄 版本管理示例

### 场景 1: 当前版本 2.21.11-SNAPSHOT
```bash
# 推送标签触发发布
git tag -a v2.21.11 -m "Release version 2.21.11"
git push origin v2.21.11

# 工作流自动执行:
# 1. 2.21.11-SNAPSHOT → 2.21.11 (发布版本)
# 2. 发布到 Maven Central
# 3. 2.21.11 → 2.21.12-SNAPSHOT (下一个开发版本)
```

### 场景 2: 当前版本 2.21.12-SNAPSHOT
```bash
# 推送标签
git tag -a v2.21.12 -m "Release version 2.21.12"
git push origin v2.21.12

# 自动:
# 1. 2.21.12-SNAPSHOT → 2.21.12
# 2. 发布
# 3. 2.21.12 → 2.21.13-SNAPSHOT
```

---

## 🔐 安全特性

- 🔐 **GPG 签名**: 所有构件都被加密签名
- 🔐 **安全存储**: GitHub Secrets 加密存储密钥
- 🔐 **自动掩盖**: 敏感信息在日志中自动隐藏
- 🔐 **Token 认证**: 使用 Sonatype token 而非密码
- 🔐 **版本验证**: 自动验证 SNAPSHOT 格式
- 🔐 **Git 权限**: 自动配置 bot 用户推送更改

---

## 📋 检查清单

发布前检查:
- [ ] GPG 密钥已生成
- [ ] 公钥已上传到 keyserver
- [ ] GitHub Secrets 已配置 (4 个)
- [ ] POM 配置已完善
- [ ] **当前版本是 SNAPSHOT 格式** (如 2.21.11-SNAPSHOT)
- [ ] 本地验证已通过
- [ ] 所有更改已提交
- [ ] 标签已推送

详细清单: 见 **[RELEASE_CHECKLIST.md](./RELEASE_CHECKLIST.md)**

---

## 💡 快速提示

1. **版本号格式**
   - 开发时: `2.21.11-SNAPSHOT`
   - 发布时: `2.21.11` (自动处理)
   - 发布后: `2.21.12-SNAPSHOT` (自动递增)

2. **标签格式**
   - 始终使用: `v2.21.11` (v 开头，无 -SNAPSHOT)

3. **发布流程**
   - 推送标签 → 自动版本管理 → 发布 → 自动递增

4. **验证发布**
   - GitHub Actions: 5-10 分钟
   - Maven Central: 5-30 分钟

---

## ❓ FAQ

### Q: 我需要做什么来发布？
A: 
1. 读 QUICK_START.md (5 分钟)
2. 配置 GitHub Secrets (4 个)
3. 确保版本是 SNAPSHOT 格式
4. 推送标签 (工作流自动处理版本号)

### Q: 版本号会自动管理吗？
A: 是的！
- 发布前: 自动移除 `-SNAPSHOT`
- 发布后: 自动递增 patch 版本并添加 `-SNAPSHOT`

### Q: 需要手动签名吗？
A: 不需要！所有都自动化了。

### Q: SNAPSHOT 和正式版有什么区别？
A: 
- SNAPSHOT: Push to master (开发版)
- Release: Push tag (正式版，自动版本管理)

---

## 🆘 遇到问题？

### 工作流失败？
→ 查看 GitHub Actions 日志
→ 阅读 `RELEASE_CHECKLIST.md` 的故障排查

### 不确定配置？
→ 运行本地验证脚本
→ 阅读 `QUICK_START.md`

### 想知道工作原理？
→ 阅读 `README_RELEASE.md`

### 需要详细说明？
→ 阅读 `MAVEN_CENTRAL_RELEASE.md`

---

## 💾 文件清单

```
.github/
├── QUICK_START.md                   ← 你在这里，向下看选择你的路径！
├── README_RELEASE.md
├── SETUP_SUMMARY.md
├── MAVEN_CENTRAL_RELEASE.md
├── RELEASE_CHECKLIST.md
├── IMPLEMENTATION_REPORT.md
│
├── workflows/
│   └── maven-release.yml            ← 新的发布工作流 (自动版本管理)
│
└── scripts/
    ├── verify-release.sh            ← Bash 验证脚本
    └── verify-release.ps1           ← PowerShell 验证脚本
```

---

## 🎉 现在就开始！

你有三种选择:

【选择 1: 快速上手】(5 分钟)
  1. 📖 读 `QUICK_START.md`
  2. 🔧 配置 GitHub Secrets
  3. 🚀 推送标签 (自动版本管理!)

【选择 2: 谨慎配置】(20 分钟)
  1. 📖 读 `SETUP_SUMMARY.md`
  2. ✅ 运行验证脚本
  3. 📋 按检查清单配置
  4. 🚀 推送标签

【选择 3: 深入了解】(30 分钟)
  1. 📖 读 `MAVEN_CENTRAL_RELEASE.md`
  2. 🔬 研究工作流细节
  3. 🔧 配置和优化
  4. 🚀 推送标签

---

**选择你的路径，现在就开始！** 👇

- [5 分钟快速开始](./QUICK_START.md) ⚡
- [工作流概览](./README_RELEASE.md) 📚
- [完整配置总结](./SETUP_SUMMARY.md) 🎯
- [详细配置指南](./MAVEN_CENTRAL_RELEASE.md) 🔍

