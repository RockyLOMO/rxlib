# Maven Central 正式发布配置指南

## 概述
本项目包含两个GitHub Actions工作流：
- `maven-snapshot.yml` - 发布 SNAPSHOT 版本（无签名）
- `maven-release.yml` - 发布正式版本（带 GPG 签名）

## 前置条件

### 1. Sonatype Maven Central 账户
- 注册 [Sonatype JIRA](https://issues.sonatype.org/)
- 创建 token（推荐，替代密码）
- 参考：[Sonatype 指南](https://central.sonatype.org/publish/publish-guide/#publishing-to-central)

### 2. GPG 密钥 (推荐使用 Kleopatra 图形界面)

#### 方法 1: 使用 Kleopatra 生成和管理密钥 (推荐)

Kleopatra 是 GPG 的图形界面工具，适合 Windows 用户操作。

##### 安装 Kleopatra
- 下载并安装 [GPG4Win](https://gpg4win.org/)
- 安装完成后启动 Kleopatra

##### 生成新密钥对
1. **打开 Kleopatra**
   - 点击 **"File" → "New Certificate"**
   - 选择 **"Create a personal OpenPGP key pair"**
   - 点击 **"Next"**

2. **填写密钥信息**
   - **Name**: 你的姓名
   - **Email**: 你的邮箱
   - **Comment**: 可选 (例如: Maven Central)
   - 点击 **"Next"**

3. **选择密钥类型**
   - **Key Material**: RSA
   - **Key Strength**: 4096 bits
   - **Valid Until**: 选择过期时间 (推荐 3 年)
   - 点击 **"Next"**

4. **创建密钥**
   - 点击 **"Create"**
   - 设置强密码 (牢记此密码！)
   - 等待密钥生成完成

##### 上传公钥到服务器
1. **选择密钥**
   - 在 Kleopatra 主界面找到刚创建的密钥
   - 右键点击 → **"Publish on Server"**

2. **选择服务器**
   - **Key Server**: keys.openpgp.org (推荐)
   - 或使用: keyserver.ubuntu.com
   - 点击 **"Publish"**

##### 导出私钥
1. **选择私钥**
   - 在 Kleopatra 主界面选择你的密钥
   - 点击 **"File" → "Export Secret Keys"**

2. **导出设置**
   - **Export destination**: 选择保存位置
   - **File name**: `private-key.asc`
   - **ASCII armor**: 确保勾选
   - 点击 **"Export"**

3. **转换为 Base64**
   ```powershell
   # PowerShell 转换命令
   $content = Get-Content -Path "private-key.asc" -Raw
   $base64 = [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($content))
   $base64 | Out-File -FilePath "private-key-base64.txt" -Encoding UTF8
   ```

#### 方法 2: 命令行方式 (备选)

如果没有 Kleopatra，也可以使用命令行：

```bash
# 生成 GPG 密钥对（如未有）
gpg --gen-key

# 查询密钥
gpg --list-secret-keys

# 导出私钥（Base64 编码，用于 GitHub Secrets）
gpg --armor --export-secret-keys YOUR_KEY_ID > private-key.asc
cat private-key.asc | base64 -w 0 > private-key-base64.txt

# 导出公钥到 GPG 服务器
gpg --send-keys YOUR_KEY_ID --keyserver keyserver.ubuntu.com
```

## GitHub Secrets 配置

在 **GitHub 仓库 Settings → Secrets and variables → Actions** 中添加：

| Secret 名称 | 说明 | 获取方式 |
|---|---|---|
| `OSSRH_USERNAME` | Sonatype 用户名 | JIRA 账户 |
| `OSSRH_TOKEN` | Sonatype token（推荐）或密码 | JIRA 账户 → Token |
| `GPG_PRIVATE_KEY` | GPG 私钥（Base64） | `cat private-key-base64.txt` |
| `GPG_PASSPHRASE` | GPG 密钥密码 | GPG 密钥生成时设置 |

### 配置示例
```yaml
# 在 GitHub → Settings → Secrets and variables → Actions
# Click "New repository secret"

OSSRH_USERNAME: your-jira-username
OSSRH_TOKEN: your-sonatype-token-xxxxx
GPG_PRIVATE_KEY: -----BEGIN PGP PRIVATE KEY BLOCK-----\n...base64 encoded...\n-----END PGP PRIVATE KEY BLOCK-----
GPG_PASSPHRASE: your-gpg-passphrase
```

## 使用方式

### 方式 1：推送标签触发（推荐）
```bash
# 1. 修改 pom.xml 版本号（移除 -SNAPSHOT）
# 修改前：<version>2.21.11-SNAPSHOT</version>
# 修改后：<version>2.21.11</version>

# 2. 提交变更
git add pom.xml rxlib/pom.xml rxlib-x/pom.xml
git commit -m "chore: release v2.21.11"

# 3. 创建标签并推送
git tag -a v2.21.11 -m "Release version 2.21.11"
git push origin v2.21.11

# 4. GitHub Actions 自动触发 maven-release.yml 工作流
# 在 GitHub 仓库 → Actions 中查看进度
```

### 方式 2：手动触发
1. 进入 **GitHub 仓库 → Actions** 
2. 选择 **"Publish Release to Maven Central (with GPG Sign)"** 工作流
3. 点击 **"Run workflow"**
4. 可选：输入版本号（默认从 pom.xml 读取）
5. 点击 **"Run workflow"** 按钮

## 工作流详解

### 版本检查
- ✅ 拒绝 SNAPSHOT 版本（例：2.21.11-SNAPSHOT）
- ✅ 必须为正式版本（例：2.21.11）

### 构建和部署
```bash
mvn clean deploy \
  -pl rxlib,rxlib-x -am \     # 发布两个模块及其依赖
  -DskipTests \               # 跳过测试（可选）
  -Dgpg.skip=false \          # 启用 GPG 签名
  --batch-mode
```

### GPG 签名
- Maven Gpg Plugin 自动使用 GitHub Actions 配置的密钥
- 通过 `gpg-passphrase` action 参数传入密码
- 所有 JAR、POM、Source 文件都会被签名

### 发布流程
1. **暂存库（Staging）**：构件上传到 Sonatype 暂存库
2. **验证**：自动验证签名和 POM 完整性
3. **发布**：手动/自动从暂存库发布到 Maven Central

## 监控发布状态

### 实时日志
- GitHub Actions 工作流页面：查看构建日志
- 搜索关键词：`Uploading`, `Staging`, `Closing`

### Sonatype 仓库
1. 登录 [Sonatype Nexus](https://oss.sonatype.org/)
2. 左侧菜单 → **Staging Repositories**
3. 搜索你的 GroupID（`com.github.rockylomo`）
4. 查看状态：
   - **OPEN**：正在上传
   - **CLOSED**：已验证，可发布
   - **RELEASED**：已发布到 Maven Central

### 发布到 Maven Central
- 自动：在暂存库关闭后自动发布
- 手动：在 Nexus 中选择仓库 → **Release** 按钮

### 可用时间
- 首次发布：几小时内
- 搜索索引：24小时内

## 常见问题

### 1. GPG 签名失败
**症状**：`gpg: signing failed`

**解决**：
```bash
# 验证 GPG 私钥格式
echo "-----BEGIN PGP PRIVATE KEY BLOCK-----" > test.txt
# 检查 Base64 编码是否正确
base64 -d private-key-base64.txt | file -
```

### 2. Sonatype 认证失败
**症状**：`401 Unauthorized`

**解决**：
- 检查 Secrets 中的用户名和 token
- 确保 token 未过期（在 Sonatype 重新生成）
- 使用 HTTPS（不是 HTTP）仓库URL

### 3. POM 验证失败
**症状**：`Missing plugin`

**解决**：
- 确保所有依赖都指定了版本号
- 运行 `mvn validate` 本地检查
- 检查 `scm`, `license`, `developer` 等必填字段

### 4. 版本号冲突
**症状**：`Repository already closed`

**解决**：
- 不能重新发布相同版本号
- 版本号必须严格递增
- 确保没有其他人同时发布相同版本

## 发布后步骤

### 1. 更新版本号
```bash
# pom.xml 版本改为下一个 SNAPSHOT
# 修改：<version>2.21.12-SNAPSHOT</version>
git add pom.xml rxlib/pom.xml rxlib-x/pom.xml
git commit -m "chore: prepare for next development iteration"
git push origin master
```

### 2. 发布 Release Notes
- GitHub Releases 中已自动创建（包含依赖信息）
- 编辑添加更多信息（新功能、Bug修复等）

### 3. 通知用户
- 更新 README.md 版本号
- 发送公告（如适用）

## pom.xml 配置示例

确保 pom.xml 包含以下必填元素：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project>
    <modelVersion>4.0.0</modelVersion>
    
    <!-- 基础信息 -->
    <groupId>com.github.rockylomo</groupId>
    <artifactId>rx</artifactId>
    <version>2.21.11</version>  <!-- 正式版本，无 -SNAPSHOT -->
    
    <!-- 必填：项目描述 -->
    <name>rx</name>
    <description>A set of utilities for Java</description>
    <url>https://github.com/RockyLOMO/rxlib</url>
    
    <!-- 必填：License -->
    <licenses>
        <license>
            <name>The Apache Software License, Version 2.0</name>
            <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
        </license>
    </licenses>
    
    <!-- 必填：SCM 信息 -->
    <scm>
        <url>https://github.com/RockyLOMO/rxlib</url>
        <connection>scm:git:https://github.com/RockyLOMO/rxlib.git</connection>
        <developerConnection>scm:git:https://github.com/RockyLOMO/rxlib.git</developerConnection>
    </scm>
    
    <!-- 必填：开发者 -->
    <developers>
        <developer>
            <id>0</id>
            <name>Rocky</name>
            <email>RockyWong.CHN@gmail.com</email>
        </developer>
    </developers>
    
    <!-- 发布仓库配置 -->
    <distributionManagement>
        <snapshotRepository>
            <id>ossrh</id>
            <url>https://central.sonatype.com/repository/maven-snapshots/</url>
        </snapshotRepository>
        <repository>
            <id>ossrh</id>
            <url>https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/</url>
        </repository>
    </distributionManagement>
    
    <!-- GPG 签名配置 -->
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-gpg-plugin</artifactId>
                <version>3.2.4</version>
                <executions>
                    <execution>
                        <id>sign-artifacts</id>
                        <phase>verify</phase>
                        <goals>
                            <goal>sign</goal>
                        </goals>
                        <configuration>
                            <gpgArguments>
                                <arg>--pinentry-mode</arg>
                                <arg>loopback</arg>
                            </gpgArguments>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

## 参考资源
- [Maven Central 发布指南](https://central.sonatype.org/publish/publish-guide/)
- [Sonatype OSSRH 指南](https://central.sonatype.org/publish/publish-maven/)
- [GPG 签名配置](https://maven.apache.org/plugins/maven-gpg-plugin/)
- [Maven Central 搜索](https://central.sonatype.com/search?q=com.github.rockylomo)

