# Maven Central 发布检查单和故障排查

## 📋 发布前检查清单

### 环境配置
- [ ] GPG 密钥已生成: `gpg --list-secret-keys`
- [ ] GPG 公钥已上传: `gpg --send-keys <KEY_ID> --keyserver keyserver.ubuntu.com`
- [ ] GitHub Secrets 已配置 (4 个):
  - [ ] `OSSRH_USERNAME`
  - [ ] `OSSRH_TOKEN`
  - [ ] `GPG_PRIVATE_KEY`
  - [ ] `GPG_PASSPHRASE`

### 项目配置
- [ ] POM 文件配置完整:
  - [ ] name, description, url 已填
  - [ ] license 配置正确 (Apache 2.0)
  - [ ] scm 信息已填
  - [ ] developers 已配置
  - [ ] distributionManagement 已配置

### 版本管理 (自动版本管理)
- [ ] **当前版本是 SNAPSHOT 格式**: `x.y.z-SNAPSHOT`
- [ ] 版本号格式正确: `2.21.11-SNAPSHOT` (不能是 `2.21.11`)
- [ ] 所有代码更改已提交到 Git
- [ ] 本地编译测试通过: `mvn clean compile test`

### 本地验证
- [ ] 运行验证脚本:
  ```bash
  # Linux/macOS
  bash .github/scripts/verify-release.sh
  
  # Windows
  powershell .github/scripts/verify-release.ps1
  ```

## 🚀 发布执行

### 新的发布方式：手动触发 + 自动标签

```bash
# 1. 确保当前版本是 SNAPSHOT
mvn help:evaluate -Dexpression=project.version -q -DforceStdout
# 应该输出: 2.21.11-SNAPSHOT

# 2. 手动触发 GitHub Action (推荐方式)
# 进入 GitHub → Actions → "Publish Release to Maven Central"
# 点击 "Run workflow" 按钮
# 参数: version="" (留空), create_tag=true

# 3. 工作流自动执行:
#    ✓ 自动创建标签 (v2.21.11)
#    ✓ 版本管理 (2.21.11-SNAPSHOT → 2.21.11 → 2.21.12-SNAPSHOT)
#    ✓ 发布到 Maven Central
#    ✓ 创建 GitHub Release
```

### 手动触发步骤

1. **进入 GitHub Actions**
   - 打开仓库 → **Actions** 标签

2. **选择工作流**
   - 找到 **"Publish Release to Maven Central (with GPG Sign & Auto Version Management)"**

3. **运行工作流**
   - 点击 **"Run workflow"**
   - 参数设置：
     - `version`: 留空 (自动检测)
     - `create_tag`: `true` (自动创建标签)

4. **监控执行**
   - 查看实时日志
   - 确认所有步骤成功

### 发布后验证
- [ ] GitHub Actions 工作流成功 (绿色 ✓)
- [ ] Git 标签自动创建: `v2.21.11`
- [ ] GitHub Release 自动创建
- [ ] Maven Central 搜索可见 (5-30 分钟)
- [ ] 版本自动递增到下一个 SNAPSHOT

## 🐛 故障排查

### 错误: "当前版本不是 SNAPSHOT 版本"

**症状**: 工作流失败，显示版本格式错误

**原因**:
- pom.xml 中的版本不是 SNAPSHOT 格式
- 版本格式不正确

**解决**:
```bash
# 检查当前版本
mvn help:evaluate -Dexpression=project.version -q -DforceStdout

# 确保是 SNAPSHOT 格式
# 正确: 2.21.11-SNAPSHOT
# 错误: 2.21.11

# 如果需要修复
# 编辑 pom.xml, rxlib/pom.xml, rxlib-x/pom.xml
# 改为: <version>2.21.11-SNAPSHOT</version>
```

### 错误: "Permission denied" 或 Git 推送失败

**症状**: 工作流在提交版本更改时失败

**原因**:
- GITHUB_TOKEN 没有推送权限
- 分支保护规则阻止推送

**解决**:
1. 检查仓库设置 → Settings → Actions → General
2. 确保 "Allow GitHub Actions to create and approve pull requests" 已启用
3. 检查分支保护规则 (如果有的话)
4. 确保工作流使用正确的 token: `token: ${{ secrets.GITHUB_TOKEN }}`

### 错误: "gpg: signing failed"

**症状**: GPG 签名步骤失败

**原因**:
- GPG_PRIVATE_KEY 格式错误
- GPG_PASSPHRASE 不正确
- GPG 公钥未上传到 keyserver

**解决**:
```bash
# 1. 验证 GPG 密钥
gpg --list-secret-keys --keyid-format=short

# 2. 测试 GPG 签名 (本地)
echo "test" | gpg --sign --armor

# 3. 确保公钥已上传
gpg --send-keys <KEY_ID> --keyserver keyserver.ubuntu.com

# 4. 检查 GitHub Secrets
# - GPG_PRIVATE_KEY: 完整的 Base64 编码
# - GPG_PASSPHRASE: 正确的密码
```

### 错误: "Repository already closed"

**症状**: Sonatype 报告版本已存在

**原因**:
- 相同版本号已发布过
- 版本号冲突

**解决**:
1. 检查 Maven Central 是否已有该版本
2. 如果需要重新发布，使用新版本号
3. 或者联系 Sonatype 支持

### 错误: "POM validation failed"

**症状**: POM 文件验证失败

**原因**:
- POM 缺少必填字段
- 依赖版本未指定
- license 或 scm 信息不完整

**解决**:
检查 pom.xml 包含:
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
    <name>开发者姓名</name>
    <email>email@example.com</email>
  </developer>
</developers>
```

### 错误: 版本递增不正确

**症状**: 发布后版本没有正确递增

**原因**:
- 版本号格式不标准 (不是 x.y.z)
- 工作流计算错误

**解决**:
确保版本号格式为: `major.minor.patch-SNAPSHOT`
- 正确: `2.21.11-SNAPSHOT`
- 错误: `2.21.11-beta-SNAPSHOT`

### 错误: 工作流循环触发

**症状**: 版本提交后触发了新的工作流

**解决**:
工作流已自动添加 `[skip ci]` 标记来防止循环:
```bash
git commit -m "chore: release v$RELEASE_VERSION [skip ci]"
```

如果仍有问题，检查仓库设置中的 CI 触发规则。

## 🔍 调试步骤

### 1. 本地验证
```bash
# 运行验证脚本
bash .github/scripts/verify-release.sh

# 检查版本格式
mvn help:evaluate -Dexpression=project.version -q -DforceStdout

# 本地编译测试
mvn clean compile test
```

### 2. 工作流调试
1. 进入 GitHub → Actions
2. 选择失败的工作流运行
3. 点击每个步骤查看详细日志
4. 查找错误信息和堆栈跟踪

### 3. 版本管理调试
```bash
# 检查 Git 历史
git log --oneline -10

# 查看版本提交
git show HEAD~1:pom.xml | grep version

# 检查标签
git tag -l | grep v
```

## 📊 常见场景和解决方案

### 场景 1: 首次发布
```
问题: 不知道从哪里开始
解决:
1. 读 QUICK_START.md (5 分钟)
2. 配置 GitHub Secrets
3. 运行验证脚本
4. 创建标签推送
```

### 场景 2: 发布失败后重试
```
问题: 工作流失败，如何重试
解决:
1. 修复问题 (见故障排查)
2. 删除失败的标签: git tag -d v2.21.11 && git push origin :v2.21.11
3. 重新创建标签: git tag -a v2.21.11 && git push origin v2.21.11
```

### 场景 3: 版本号错误
```
问题: 发布了错误的版本号
解决:
1. 下一个发布会自动递增
2. 或者手动调整版本号
3. 确保下次标签正确
```

### 场景 4: Git 仓库状态混乱
```
问题: 版本提交导致仓库状态不一致
解决:
1. 检查 git status
2. 如果需要，reset 到干净状态
3. 重新开始发布流程
```

## 📞 获取帮助

### 快速诊断
运行诊断脚本获取详细信息:
```bash
bash .github/scripts/verify-release.sh
```

### 工作流日志
- GitHub Actions → 工作流运行 → 查看每个步骤的日志
- 搜索关键词: ERROR, FAILED, Exception

### 社区支持
- 检查 GitHub Issues (类似问题)
- Maven Central 文档: https://central.sonatype.org/
- GPG 签名指南: https://maven.apache.org/plugins/maven-gpg-plugin/

## 🎯 最佳实践

### 发布前
- [ ] 运行本地验证脚本
- [ ] 确保所有测试通过
- [ ] 检查版本号格式
- [ ] 备份重要文件

### 发布中
- [ ] 监控 GitHub Actions 进度
- [ ] 保存工作流运行链接
- [ ] 准备好回滚计划

### 发布后
- [ ] 验证 Maven Central 可见性
- [ ] 检查 GitHub Release 创建
- [ ] 确认版本自动递增
- [ ] 更新相关文档

## 📈 性能和时间

### 预期时间
- **工作流执行**: 5-10 分钟
- **Maven Central 索引**: 5-30 分钟
- **本地验证**: 1-2 分钟

### 性能优化
- 确保网络连接稳定
- 使用最新的 Maven 和 JDK 版本
- 定期清理工作流运行历史

---

## 📋 完整检查清单 (复制使用)

发布前检查:
- [ ] GPG 密钥配置正确
- [ ] GitHub Secrets 已设置
- [ ] POM 文件配置完整
- [ ] 当前版本是 SNAPSHOT 格式
- [ ] 本地验证通过
- [ ] 代码已提交

发布执行:
- [ ] 标签格式正确 (v开头)
- [ ] 推送标签触发工作流
- [ ] 监控工作流执行
- [ ] 验证发布成功

发布后:
- [ ] GitHub Release 创建
- [ ] Maven Central 可见
- [ ] 版本自动递增
- [ ] 准备下次开发

---

**记住**: 大多数问题都可以通过运行验证脚本和检查版本格式来解决！

```bash
# 快速诊断
bash .github/scripts/verify-release.sh
```
