# 🚀 Maven Central 发布指南

## 📋 核心功能

**自动版本管理 + GPG 签名发布**

- ✅ **手动触发**: GitHub Actions 页面启动
- ✅ **自动标签**: 工作流内部创建 `v{版本号}` 标签
- ✅ **版本管理**: `2.21.11-SNAPSHOT` → `2.21.11` → `2.21.12-SNAPSHOT`
- ✅ **GPG 签名**: 所有构件加密签名
- ✅ **一键发布**: 到 Maven Central

---

## ⚡ 快速开始 (5 分钟)

### 1. 生成 GPG 密钥 (Kleopatra 推荐)

**Windows 用户**:
1. 下载 [GPG4Win](https://gpg4win.org/) 并安装
2. 启动 Kleopatra
3. File → New Certificate → Create personal OpenPGP key pair
4. 填写姓名、邮箱，选择 RSA 4096 bits
5. 设置密码并创建
6. 右键密钥 → Publish on Server (选择 keys.openpgp.org)
7. File → Export Secret Keys (保存为 `private-key.asc`)
8. 转换为 Base64:
   ```powershell
   $content = Get-Content private-key.asc -Raw
   [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($content)) | Out-File private-key-base64.txt
   ```

### 2. 配置 GitHub Secrets

进入 GitHub → 仓库 → Settings → Secrets and variables → Actions

添加 4 个 Secrets:
- `OSSRH_USERNAME`: Sonatype 用户名
- `OSSRH_TOKEN`: Sonatype token
- `GPG_PRIVATE_KEY`: 上面生成的 Base64 私钥
- `GPG_PASSPHRASE`: GPG 密钥密码

### 3. 发布版本

**新方式**: 手动触发 + 自动标签

1. 确保 `pom.xml` 版本是 SNAPSHOT 格式 (如 `2.21.11-SNAPSHOT`)
2. 进入 GitHub → Actions
3. 选择 "Publish Release to Maven Central (with GPG Sign & Auto Version Management)"
4. 点击 "Run workflow"
5. 参数设置:
   - `version`: 留空 (自动检测)
   - `create_tag`: `true` (自动创建标签)
6. 点击 "Run workflow"

**工作流自动执行**:
- ✅ 解析版本号
- ✅ 创建 `v2.21.11` 标签并推送
- ✅ 更新版本: `2.21.11-SNAPSHOT` → `2.21.11`
- ✅ 发布到 Maven Central (带 GPG 签名)
- ✅ 创建 GitHub Release
- ✅ 递增版本: `2.21.11` → `2.21.12-SNAPSHOT`

---

## 🔧 配置要求

### POM 文件必填字段
```xml
<project>
    <name>项目名称</name>
    <description>项目描述</description>
    <url>https://github.com/user/repo</url>

    <licenses>
        <license>
            <name>The Apache Software License, Version 2.0</name>
            <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
        </license>
    </licenses>

    <scm>
        <url>https://github.com/user/repo</url>
        <connection>scm:git:https://github.com/user/repo.git</connection>
    </scm>

    <developers>
        <developer>
            <name>开发者姓名</name>
            <email>email@example.com</email>
        </developer>
    </developers>
</project>
```

### 本地验证
```bash
# Linux/macOS
bash .github/scripts/verify-release.sh

# Windows
powershell .github/scripts/verify-release.ps1
```

---

## 📊 版本管理示例

| 当前版本 | 发布版本 | 标签 | 下一个开发版本 |
|----------|----------|------|----------------|
| 2.21.11-SNAPSHOT | 2.21.11 | v2.21.11 | 2.21.12-SNAPSHOT |
| 2.21.12-SNAPSHOT | 2.21.12 | v2.21.12 | 2.21.13-SNAPSHOT |

---

## 🐛 常见问题

### GPG 签名失败
```
原因: 私钥格式错误或密码不对
解决: 检查 GPG_PRIVATE_KEY 和 GPG_PASSPHRASE
```

### 版本号错误
```
原因: 不是 SNAPSHOT 格式
解决: 确保版本格式为 x.y.z-SNAPSHOT
```

### 发布失败
```
原因: Sonatype 认证失败
解决: 检查 OSSRH_USERNAME 和 OSSRH_TOKEN
```

### 标签创建失败
```
原因: 权限不足
解决: 检查 GITHUB_TOKEN 权限设置
```

---

## 📈 验证发布成功

### GitHub Actions
- 工作流状态: ✅ 所有步骤通过
- 标签创建: `v{版本号}` 已推送

### Maven Central
```bash
# 5-30 分钟后搜索
https://central.sonatype.com/search?q=com.github.rockylomo
```

### GitHub Release
- 自动创建 Release: `v{版本号}`
- 包含依赖信息和发布说明

---

## 🔗 重要链接

- **Maven Central 搜索**: https://central.sonatype.com/search?q=com.github.rockylomo
- **Sonatype OSSRH**: https://oss.sonatype.org/
- **GPG4Win 下载**: https://gpg4win.org/
- **发布指南**: https://central.sonatype.org/publish/publish-guide/

---

## 📋 检查清单

发布前检查:
- [ ] GPG 密钥已生成 (Kleopatra)
- [ ] 公钥已上传到 keyserver
- [ ] GitHub Secrets 已配置 (4 个)
- [ ] POM 文件配置完整
- [ ] 当前版本是 SNAPSHOT 格式
- [ ] 本地验证通过

发布执行:
- [ ] 手动触发 GitHub Action
- [ ] 参数设置正确
- [ ] 工作流运行成功

发布验证:
- [ ] Git 标签自动创建
- [ ] GitHub Release 创建
- [ ] Maven Central 可见

---

## 🎯 核心优势

1. **自动化**: 一键发布，自动处理版本和标签
2. **安全**: GPG 签名 + Secrets 加密
3. **灵活**: 支持手动指定版本号
4. **可靠**: 完善的错误处理和验证
5. **用户友好**: 图形界面 + 详细文档

---

**现在就开始发布你的第一个版本吧！** 🚀

---

*最后更新: 2026-04-03*
*版本: 1.0*
*状态: ✅ 精简版*
