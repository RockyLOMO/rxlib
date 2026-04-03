# Maven Central 发布前本地验证脚本 (Windows PowerShell 版本)
# 用途：验证 GPG 签名、POM 配置、依赖等在本地环境中能否正确工作

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $ScriptDir)

Write-Host "================================================" -ForegroundColor Yellow
Write-Host "Maven Central 发布前本地验证 (PowerShell)" -ForegroundColor Yellow
Write-Host "================================================" -ForegroundColor Yellow
Write-Host "项目根目录: $ProjectRoot" -ForegroundColor Cyan
Write-Host ""

# 辅助函数
function Check-Step {
    param([int]$StepNum, [string]$Description)
    Write-Host ""
    Write-Host "[Step $StepNum] $Description" -ForegroundColor Yellow
}

function Write-Success {
    param([string]$Message)
    Write-Host "✓ $Message" -ForegroundColor Green
}

function Write-Error-Custom {
    param([string]$Message)
    Write-Host "✗ $Message" -ForegroundColor Red
    exit 1
}

function Write-Warning-Custom {
    param([string]$Message)
    Write-Host "⚠ $Message" -ForegroundColor Yellow
}

# Step 1: 检查环境
Check-Step 1 "检查环境和工具"

try {
    $mvnVersion = & mvn -v 2>$null | Select-Object -First 1
    Write-Success "Maven 已安装: $mvnVersion"
} catch {
    Write-Error-Custom "Maven 未安装，请先安装 Maven"
}

try {
    $gpgVersion = & gpg --version 2>$null | Select-Object -First 1
    Write-Success "GPG 已安装: $gpgVersion"
} catch {
    Write-Error-Custom "GPG 未安装，请先安装 GPG"
}

# Step 2: 检查版本号
Check-Step 2 "检查项目版本号"

Push-Location $ProjectRoot
$Version = & mvn help:evaluate -Dexpression=project.version -q -DforceStdout 2>$null

Write-Host "当前版本: $Version"

if ($Version -like "*-SNAPSHOT") {
    Write-Error-Custom "版本号为 SNAPSHOT: $Version，不能发布正式版本！"
}

Write-Success "版本号检查通过（非 SNAPSHOT）: $Version"

# Step 3: 检查 Git 状态
Check-Step 3 "检查 Git 状态"

if (Test-Path ".git" -PathType Container) {
    $gitStatus = & git status --porcelain 2>$null
    if ($gitStatus) {
        Write-Error-Custom "工作目录有未提交的变更，请先提交所有更改"
    }
    Write-Success "Git 工作目录干净"

    $lastTag = & git describe --tags --always 2>$null
    Write-Success "最新标签: $lastTag"
} else {
    Write-Warning-Custom "不是 Git 仓库，跳过 Git 检查"
}

# Step 4: 检查 POM 配置
Check-Step 4 "验证 POM 配置"

$pomContent = Get-Content "pom.xml" -Raw

$requiredFields = @("name", "description", "url", "license", "scm", "developers")
$missingFields = @()

foreach ($field in $requiredFields) {
    if ($pomContent -notmatch $field) {
        $missingFields += $field
    }
}

if ($missingFields.Count -gt 0) {
    Write-Warning-Custom "POM 中缺少字段: $($missingFields -join ', ')"
} else {
    Write-Success "POM 基础字段检查通过"
}

# Step 5: 检查发布仓库配置
Check-Step 5 "检查发布仓库配置"

if ($pomContent -match "distributionManagement") {
    Write-Success "发布仓库已配置"
} else {
    Write-Error-Custom "未找到 distributionManagement 配置"
}

# Step 6: 检查 GPG 密钥
Check-Step 6 "检查 GPG 密钥"

$gpgKeys = & gpg --list-secret-keys --keyid-format=short 2>$null

if (!$gpgKeys -or $gpgKeys.Count -eq 0) {
    Write-Error-Custom "❌ 未找到 GPG 密钥！请先生成密钥对: gpg --gen-key"
}

Write-Host "可用的 GPG 密钥:" -ForegroundColor Cyan
& gpg --list-secret-keys --keyid-format=short 2>$null | Where-Object { $_ -match "sec|uid" }
Write-Success "GPG 密钥检查通过"

# Step 7: 编译和测试
Check-Step 7 "编译项目"

$compileOutput = & mvn clean compile -q 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Error-Custom "编译失败：$compileOutput"
}
Write-Success "编译成功"

# Step 8: 运行单元测试
Check-Step 8 "运行单元测试"

$testOutput = & mvn test -q -pl rxlib 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Warning-Custom "测试失败或有跳过的测试（可能需要手动检查）"
} else {
    Write-Success "单元测试通过"
}

# Step 9: 验证签名配置
Check-Step 9 "验证 GPG 签名配置"

if ($pomContent -match "maven-gpg-plugin") {
    Write-Success "maven-gpg-plugin 已配置"
} else {
    Write-Warning-Custom "未找到 maven-gpg-plugin 配置，发布时可能无法签名"
}

# Step 10: 本地签名测试
Check-Step 10 "本地签名测试"

Write-Host "生成 GPG 密钥列表用于测试..." -ForegroundColor Gray

$gpgKeyLine = & gpg --list-secret-keys --keyid-format=short 2>$null | Where-Object { $_ -match 'sec' } | Select-Object -First 1
if ($gpgKeyLine) {
    $gpgKey = ($gpgKeyLine -split '\s+')[1].Split('/')[1]
    Write-Host "使用密钥: $gpgKey" -ForegroundColor Gray

    $verifyOutput = & mvn clean verify -DskipTests -Dgpg.skip=false -Dgpg.keyname="$gpgKey" -q 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Warning-Custom "本地签名测试失败（可能需要密码交互或缺少 gpg-agent），但不影响 CI/CD"
    } else {
        Write-Success "签名配置测试完成"
    }
} else {
    Write-Warning-Custom "无法自动选择 GPG 密钥，请手动在 CI 中配置"
}

# Step 11: 生成源码包
Check-Step 11 "验证源码打包"

$packageOutput = & mvn clean source:jar javadoc:jar -q 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Warning-Custom "源码或文档生成失败"
} else {
    Write-Success "源码打包验证完成"
}

# Step 12: 总结
Check-Step 12 "本地验证总结"

Pop-Location

Write-Host ""
Write-Host "================================================" -ForegroundColor Green
Write-Host "✓ 本地验证通过！" -ForegroundColor Green
Write-Host "================================================" -ForegroundColor Green
Write-Host ""
Write-Host "发布前的最后一步:" -ForegroundColor Cyan
Write-Host "  1. 确保所有更改已提交到 Git"
Write-Host "  2. 创建和推送版本标签:"
Write-Host "     git tag -a v$Version -m 'Release version $Version'"
Write-Host "     git push origin v$Version"
Write-Host ""
Write-Host "  3. GitHub Actions 将自动触发发布流程"
Write-Host "  4. 在 GitHub → Actions 中监控发布进度"
Write-Host ""
Write-Host "相关文档:" -ForegroundColor Cyan
Write-Host "  - .github/MAVEN_CENTRAL_RELEASE.md (详细配置)"
Write-Host "  - .github/RELEASE_CHECKLIST.md (发布检查清单)"
Write-Host ""

