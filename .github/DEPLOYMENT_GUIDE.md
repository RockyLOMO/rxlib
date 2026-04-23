# Maven Central 发布与部署说明

本文档合并了原「快速开始」与部署要点：**GPG 与 Secrets 一次性配置**、**手动触发发布**、**验证与排错**。

---

## 核心能力

- 在 GitHub Actions 中**手动触发**（不再依赖推送 tag 触发）
- **自动**创建并推送 `v{版本号}` 标签（`create_tag=true` 时）
- 版本流：`x.y.z-SNAPSHOT` → 发布 `x.y.z` → 下一开发版 `x.y.(z+1)-SNAPSHOT`
- 构件经 **GPG 签名** 后发布至 Maven Central，并创建 GitHub Release

---

## 一、一次性配置

### 1. GPG 密钥（推荐 Kleopatra / GPG4Win）

1. 安装 [GPG4Win](https://gpg4win.org/)，打开 **Kleopatra**
2. **File → New Certificate → Create personal OpenPGP key pair**，RSA **4096**，填姓名/邮箱/密码
3. 右键密钥 → **Publish on Server**（如 `keys.openpgp.org` 或 `keyserver.ubuntu.com`）
4. **File → Export Secret Keys** → 保存为 `private-key.asc`（ASCII armor）
5. 转为 Base64（供 `GPG_PRIVATE_KEY` 使用）：

**Windows (PowerShell)**

```powershell
$content = Get-Content private-key.asc -Raw
[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($content)) | Out-File private-key-base64.txt -Encoding utf8
```

**Linux / macOS**

```bash
gpg --armor --export-secret-keys KEY_ID > private-key.asc
base64 -w 0 < private-key.asc > private-key-base64.txt
```

**命令行生成密钥（不装图形界面时）**

```bash
gpg --gen-key   # RSA 4096，设好邮箱与密码
gpg --list-secret-keys --keyid-format=short
gpg --send-keys KEY_ID --keyserver keyserver.ubuntu.com
```

将 `KEY_ID` 记好；私钥导出与 Base64 步骤同上。

### 2. GitHub Secrets

**Settings → Secrets and variables → Actions** 中新增 4 项：

| Secret | 说明 |
|--------|------|
| `OSSRH_USERNAME` | Sonatype / JIRA 账号 |
| `OSSRH_TOKEN` | Sonatype 用户 **Token**（比密码更安全） |
| `GPG_PRIVATE_KEY` | 上面 Base64 的**整段**私钥内容 |
| `GPG_PASSPHRASE` | 创建 GPG 密钥时的密码，须完全一致 |

### 3. `pom.xml` 发布元数据

需含 `name`、`description`、`url`、`licenses`、`scm`、`developers`（按仓库与 Central 要求填写）：

```xml
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
    <name>开发者</name>
    <email>email@example.com</email>
  </developer>
</developers>
```

### 4. 本地校验（发布前建议执行）

```bash
# Linux / macOS
bash .github/scripts/verify-release.sh

# Windows
powershell .github/scripts/verify-release.ps1
```

---

## 二、发布操作

1. 确保 `pom.xml` 中版本为 **SNAPSHOT**（如 `2.21.11-SNAPSHOT`），代码已提交
2. **GitHub → Actions** → 选择 **Publish Release to Maven Central (with GPG Sign & Auto Version Management)**
3. **Run workflow**
4. 建议参数：
   - **`version`**：留空 → 从 `pom.xml` 读出版本并去掉 `-SNAPSHOT`
   - **`create_tag`**：`true` → 自动创建并推送 `v2.21.11` 等标签
5. 运行后工作流会大致完成：解析版本 → 创建/使用 tag → 置为 Release 版本 → `mvn deploy`（GPG 签名）→ 生成 Release 说明与 GitHub Release → 将版本递增至下一 **SNAPSHOT** 并提交

**GitHub CLI（可选）**

```bash
gh workflow run "Publish Release to Maven Central (with GPG Sign & Auto Version Management)" \
  -f version="" \
  -f create_tag=true
```

**说明**

- 现以**手动 Run workflow** 为主；旧版「仅推送 tag 即发布」若已弃用，以本仓库工作流为准。
- `GITHUB_TOKEN` 需具备创建/推送 tag 的权限（仓库 **Settings → Actions → General** 中确认）。
- 若 tag 已存在，工作流通常会**复用**该 tag，不强制覆盖。

---

## 三、版本与参数

| 当前 pom 版本 | `version` 留空时发布 | 下一开发版（典型） |
|---------------|----------------------|--------------------|
| 2.21.11-SNAPSHOT | 2.21.11 | 2.21.12-SNAPSHOT |

也可在触发时**手动填** `version`（如 `2.21.15`），以指定发布号；下一 SNAPSHOT 会随工作流逻辑递增。

`create_tag=false` 时跳过自动打 tag，仍可按工作流做版本与部署（视具体 workflow 是否支持）。

当前版本可本地查看：

```bash
mvn help:evaluate -Dexpression=project.version -q -DforceStdout
```

---

## 四、发布成功如何确认

- **Actions**：各步骤通过，无失败
- **Tags**：出现 `v{版本号}` 并曾推送成功
- **Releases**：对应 Release 与说明已生成
- **Maven Central**：约 **5–30 分钟** 后可在 [Central 搜索](https://central.sonatype.com/) 中检索到坐标（以本仓库 `groupId` / `artifactId` 为准）

---

## 五、常见问题

| 现象 | 处理方向 |
|------|----------|
| GPG 签名失败 | 核对 `GPG_PRIVATE_KEY` 是否完整、Base64 是否正确；`GPG_PASSPHRASE` 是否与建钥时一致 |
| 版本不对 | 确认 pom 为 `x.y.z-SNAPSHOT`；或检查手动填的 `version` |
| 发布 / 认证失败 | 检查 `OSSRH_USERNAME`、`OSSRH_TOKEN` |
| 打 tag 失败 | 查 Actions 权限、是否已有冲突 tag、默认分支是否可写 |

---

## 六、检查清单

**发布前**  
- [ ] GPG 已生成，公钥已上 key server  
- [ ] 上述 4 个 Secrets 已配置  
- [ ] pom 元数据完整，当前为 SNAPSHOT  
- [ ] `verify-release` 脚本通过  

**发布后**  
- [ ] 工作流成功、tag 与（如有）Release 正常  
- [ ] Central 可搜到新版本（耐心等待同步）  

---

## 七、相关链接

- [Central 发布总览](https://central.sonatype.org/publish/publish-guide/)
- [GPG4Win](https://gpg4win.org/)
- Sonatype OSSRH / JIRA 账户与 Token 在官网个人资料中维护

