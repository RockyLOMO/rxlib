# 🚀 Maven Central 发布配置 - 初始化指南

> **TL;DR**: 5步完成配置，然后就能自动发布了
> 
> **✨ 新功能**: 自动版本管理！发布时自动处理版本号更新。

## 第一步：生成 GPG 密钥（Kleopatra 图形界面）

**推荐使用 Kleopatra (Windows GPG 图形界面工具)**

### 方法 1: 使用 Kleopatra 生成密钥

1. **打开 Kleopatra**
   - 搜索 "Kleopatra" 或从 GPG4Win 安装包启动

2. **生成新密钥对**
   - 点击 **"File" → "New Certificate"**
   - 选择 **"Create a personal OpenPGP key pair"**
   - 点击 **"Next"**

3. **填写密钥信息**
   - **Name**: 你的姓名 (例如: Rocky)
   - **Email**: 你的邮箱 (例如: RockyWong.CHN@gmail.com)
   - **Comment**: 可选 (例如: Maven Central)
   - 点击 **"Next"**

4. **选择密钥类型**
   - **Key Material**: RSA
   - **Key Strength**: 4096 bits (推荐)
   - **Valid Until**: 选择过期时间 (推荐: 3 年后)
   - 点击 **"Next"**

5. **创建密钥**
   - 点击 **"Create"**
   - 设置强密码 (牢记此密码！)
   - 等待密钥生成完成

6. **验证密钥创建**
   - 在 Kleopatra 主界面看到新创建的密钥
   - 状态显示为 "Valid"

### 方法 2: 命令行生成 (备选)

如果没有 Kleopatra，也可以使用命令行：

```bash
# 交互式生成密钥
gpg --gen-key

# 选择项（推荐值）：
# - Key type: RSA (1)
# - Key size: 4096
# - Validity: 0 (永久有效) 或 3y (3年)
# - Real name: 你的名字
# - Email: 你的邮箱
# - Passphrase: 输入密钥密码 (需要记住！)

# 查看生成的密钥
gpg --list-secret-keys --keyid-format=short
```

输出示例：
```
sec   rsa4096/A1B2C3D4 2026-04-02 [SC]
      XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX
uid                 [ultimate] Rocky <RockyWong.CHN@gmail.com>
ssb   rsa4096/E5F6G7H8 2026-04-02 [E]
```

记住 `A1B2C3D4` 这个 KEY_ID

## 第二步：上传公钥到 GPG 服务器

### 使用 Kleopatra 上传公钥

1. **在 Kleopatra 中选择密钥**
   - 找到刚创建的密钥
   - 右键点击 → **"Publish on Server"**

2. **选择密钥服务器**
   - **Key Server**: keys.openpgp.org (推荐)
   - 或使用: keyserver.ubuntu.com
   - 点击 **"Publish"**

3. **验证上传成功**
   - 等待上传完成
   - 或者稍后验证: 搜索你的邮箱确认公钥可见

### 命令行上传 (备选)

```bash
# 上传公钥（必须做，否则 Maven Central 签名验证会失败）
gpg --send-keys A1B2C3D4 --keyserver keyserver.ubuntu.com

# 验证上传成功（2-3 分钟后生效）
gpg --recv-keys A1B2C3D4 --keyserver keyserver.ubuntu.com
```

## 第三步：导出私钥并编码

### 使用 Kleopatra 导出私钥

1. **选择私钥**
   - 在 Kleopatra 主界面选择你的密钥
   - 点击 **"File" → "Export Secret Keys"**

2. **导出设置**
   - **Export destination**: 选择保存位置
   - **File name**: `private-key.asc`
   - **ASCII armor**: 确保勾选
   - 点击 **"Export"**

3. **输入密码**
   - 输入创建密钥时设置的密码
   - 等待导出完成

4. **转换为 Base64**
   ```bash
   # 在命令行中转换 (Windows PowerShell)
   $content = Get-Content -Path "private-key.asc" -Raw
   $base64 = [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($content))
   $base64 | Out-File -FilePath "private-key-base64.txt" -Encoding UTF8
   
   # 或者使用在线工具转换
   # 将 private-key.asc 的内容复制到在线 Base64 编码器
   ```

5. **验证 Base64 内容**
   - 打开 `private-key-base64.txt`
   - 确保内容以 `-----BEGIN PGP PRIVATE KEY BLOCK-----` 开始
   - 复制整个内容用于 GitHub Secrets

### 命令行导出 (备选)

```bash
# 导出为 ASCII armor 格式
gpg --armor --export-secret-keys A1B2C3D4 > private-key.asc

# 转换为 Base64（用于 GitHub Secrets）
base64 -w 0 < private-key.asc > private-key-base64.txt

# 查看结果（复制整个内容到 GitHub）
cat private-key-base64.txt
```

## 第四步：在 GitHub 配置 Secrets

进入你的仓库：

1. **Settings** → **Secrets and variables** → **Actions**
2. 点击 **New repository secret**，添加以下 4 个：

### Secret 1: `OSSRH_USERNAME`
- **值**: 你的 Sonatype JIRA 用户名
- **获取**: https://issues.sonatype.org/ 账户

### Secret 2: `OSSRH_TOKEN`
- **值**: Sonatype token（强烈推荐，比密码更安全）
- **获取**: JIRA → Profile → Tokens → New Token → 复制 token 值

### Secret 3: `GPG_PRIVATE_KEY`
- **值**: 上面导出的 Base64 编码的完整私钥
- **来源**: `private-key-base64.txt` 的内容

### Secret 4: `GPG_PASSPHRASE`
- **值**: 生成 GPG 密钥时设置的密码
- **要求**: 必须与密钥生成时的密码完全一致

## 第五步：发布版本（自动版本管理和标签创建！）

**✨ 现在版本号会自动管理，标签也会自动创建！**

### 发布流程

```bash
# 1. 确保当前版本是 SNAPSHOT 格式
# pom.xml 中应该是: <version>2.21.11-SNAPSHOT</version>

# 2. 手动触发 GitHub Action (无需推送标签！)
# 进入 GitHub → Actions → "Publish Release to Maven Central"
# 点击 "Run workflow" 按钮

# 3. 填写参数:
#    version: (可选) 2.21.11 (留空自动从 pom.xml 检测)
#    create_tag: (推荐) true (自动创建和推送标签)

# 4. 点击 "Run workflow" 开始发布

# 5. 工作流自动执行:
#    ✓ 解析版本号 (2.21.11-SNAPSHOT)
#    ✓ 自动创建标签 (v2.21.11)
#    ✓ 推送标签到远程
#    ✓ 2.21.11-SNAPSHOT → 2.21.11 (发布版本)
#    ✓ 发布到 Maven Central (带 GPG 签名)
#    ✓ 2.21.11 → 2.21.12-SNAPSHOT (下一个开发版本)
```

### 手动触发步骤详解

#### 方式 1: 通过 GitHub Actions 页面

1. **进入 GitHub Actions**
   - 打开你的 GitHub 仓库
   - 点击 **"Actions"** 标签

2. **选择工作流**
   - 找到 **"Publish Release to Maven Central (with GPG Sign & Auto Version Management)"**
   - 点击 **"Run workflow"** 按钮

3. **填写参数**
   - **Use workflow from**: `main` (默认)
   - **version**: 
     - 留空: 自动从 `pom.xml` 检测版本 (推荐)
     - 或输入: `2.21.11` (指定版本号)
   - **create_tag**: 
     - ✅ `true` (自动创建和推送标签，推荐)
     - ❌ `false` (不创建标签，仅发布)

4. **运行工作流**
   - 点击 **"Run workflow"** 绿色按钮
   - 工作流开始执行

#### 方式 2: 通过 GitHub CLI (可选)

```bash
# 使用 GitHub CLI 触发工作流
gh workflow run "Publish Release to Maven Central (with GPG Sign & Auto Version Management)" \
  -f version="" \
  -f create_tag=true
```

### 参数说明

| 参数 | 说明 | 默认值 | 推荐 |
|------|------|--------|------|
| `version` | 发布版本号，留空自动检测 | 空 | 留空自动检测 |
| `create_tag` | 是否自动创建和推送 Git 标签 | `true` | `true` (推荐) |

### 自动检测版本逻辑

当 `version` 参数留空时:
- 从 `pom.xml` 读取当前版本
- 自动移除 `-SNAPSHOT` 后缀
- 示例: `2.21.11-SNAPSHOT` → `2.21.11`

### 自动标签创建

当 `create_tag=true` 时:
- 自动创建标签: `v{版本号}`
- 自动推送到远程仓库
- 示例: 创建 `v2.21.11` 标签

---

## 🎯 完整发布流程示例

### 场景: 当前版本 2.21.11-SNAPSHOT

```bash
# 步骤 1: 检查当前版本
mvn help:evaluate -Dexpression=project.version -q -DforceStdout
# 输出: 2.21.11-SNAPSHOT

# 步骤 2: 手动触发 GitHub Action
# GitHub → Actions → 选择工作流 → Run workflow
# 参数: version="" (留空), create_tag=true

# 步骤 3: 工作流自动执行
# 3.1 解析版本: 2.21.11-SNAPSHOT
# 3.2 自动创建标签: v2.21.11
# 3.3 推送标签到远程
# 3.4 更新版本: 2.21.11-SNAPSHOT → 2.21.11
# 3.5 提交更改: push 到 main 分支
# 3.6 发布: mvn deploy (带 GPG 签名)
# 3.7 创建 GitHub Release
# 3.8 递增版本: 2.21.11 → 2.21.12-SNAPSHOT
# 3.9 提交新版本: push 到 main 分支

# 步骤 4: 验证结果
# - Git 标签: v2.21.11 (自动创建)
# - GitHub Release: 自动创建
# - Maven Central: 5-30 分钟后可见
# - 下一个开发版本: 2.21.12-SNAPSHOT (自动递增)
```

---

## 📋 发布前检查清单

- [ ] GPG 密钥已生成 (Kleopatra)
- [ ] 公钥已上传到 keyserver (Kleopatra)
- [ ] GitHub Secrets 已配置 (4 个)
- [ ] **当前版本是 SNAPSHOT 格式** (如 2.21.11-SNAPSHOT)
- [ ] 所有代码更改已提交
- [ ] 本地验证通过: `bash .github/scripts/verify-release.sh`

---

## 🔍 工作流执行详情

### 工作流步骤详解

1. **Parse current version** - 读取 pom.xml 版本号，支持手动指定
2. **Create and push git tag** - 自动创建和推送 Git 标签
3. **Update version to release** - 移除 `-SNAPSHOT` 后缀
4. **Commit version update** - 提交版本更改到 Git
5. **Deploy Release** - 发布到 Maven Central (带签名)
6. **Create Release Notes** - 生成发布说明
7. **Create GitHub Release** - 创建 GitHub Release
8. **Prepare next development version** - 计算下一个 SNAPSHOT 版本
9. **Commit next development version** - 提交新版本到 Git

### 版本计算逻辑

```bash
# 输入参数或自动检测
INPUT_VERSION="${{ github.event.inputs.version }}"
if [[ -n "$INPUT_VERSION" ]]; then
  RELEASE_VERSION="$INPUT_VERSION"  # 使用指定版本
else
  RELEASE_VERSION="${CURRENT_VERSION%-SNAPSHOT}"  # 自动检测
fi

# 递增 patch 版本
VERSION_PARTS=(2 21 11)
PATCH=$((VERSION_PARTS[2] + 1))
NEXT_SNAPSHOT="${VERSION_PARTS[0]}.${VERSION_PARTS[1]}.$PATCH-SNAPSHOT"
```

---

## ⚠️ 重要注意事项

### 触发方式变更
- **之前**: 推送标签自动触发
- **现在**: 手动触发 Action，内部自动创建标签

### 参数使用
- **version**: 留空自动检测 (推荐)
- **create_tag**: 设为 true 自动创建标签 (推荐)

### 权限要求
- 工作流需要 `GITHUB_TOKEN` 权限来创建和推送标签
- 确保仓库设置中 Actions 有适当权限

### 标签处理
- 如果标签已存在，会使用现有标签
- 不会覆盖已存在的标签
- 标签格式: `v{版本号}`

---

## 🐛 常见问题

### Q: 如何手动指定版本号？
```
在触发工作流时填写 version 参数
例如: version="2.21.11"
工作流会使用指定的版本号而不是自动检测
```

### Q: 如果不想自动创建标签怎么办？
```
将 create_tag 参数设为 false
工作流会跳过标签创建步骤
但仍会进行版本管理和发布
```

### Q: 标签已存在怎么办？
```
工作流会检测到现有标签并使用它
不会重新创建或覆盖现有标签
```

### Q: 如何查看工作流运行状态？
```
GitHub → Actions → 选择工作流运行
查看每个步骤的日志和状态
```

---

## 📊 版本管理示例

### 自动检测版本
| 当前版本 | 指定版本 | 发布版本 | 下一个开发版本 |
|----------|----------|----------|----------------|
| 2.21.11-SNAPSHOT | (留空) | 2.21.11 | 2.21.12-SNAPSHOT |
| 2.21.12-SNAPSHOT | (留空) | 2.21.12 | 2.21.13-SNAPSHOT |

### 手动指定版本
| 当前版本 | 指定版本 | 发布版本 | 下一个开发版本 |
|----------|----------|----------|----------------|
| 2.21.11-SNAPSHOT | 2.21.15 | 2.21.15 | 2.21.16-SNAPSHOT |
| 2.21.12-SNAPSHOT | 3.0.0 | 3.0.0 | 3.0.1-SNAPSHOT |

---

## 🎉 现在准备好了！

### 快速验证
```bash
# 检查当前版本
mvn help:evaluate -Dexpression=project.version -q -DforceStdout

# 运行本地验证
bash .github/scripts/verify-release.sh
```

### 立即发布
```bash
# 进入 GitHub Actions 页面
# 选择 "Publish Release to Maven Central" 工作流
# 点击 "Run workflow"
# 参数: version="" (留空), create_tag=true
# 点击运行！

# 观看自动化版本管理和标签创建！
```

---

## 📚 相关文档

- **START_HERE.md** - 快速导航
- **README_RELEASE.md** - 工作流概览
- **SETUP_SUMMARY.md** - 配置总结
- **RELEASE_CHECKLIST.md** - 故障排查
- **MAVEN_CENTRAL_RELEASE.md** - 详细指南

---

**准备好了？** 👉 现在进入 GitHub Actions 手动触发发布！
