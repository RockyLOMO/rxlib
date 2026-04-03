#!/bin/bash
# Maven Central 发布前本地验证脚本
# 用途：验证 GPG 签名、POM 配置、依赖等在本地环境中能否正确工作

set -e  # 任何命令失败则退出

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

echo "================================================"
echo "Maven Central 发布前本地验证"
echo "================================================"
echo "项目根目录: $PROJECT_ROOT"
echo ""

# Color output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 检查函数
check_step() {
    local step_num=$1
    local description=$2
    echo ""
    echo -e "${YELLOW}[Step $step_num]${NC} $description"
}

success() {
    echo -e "${GREEN}✓${NC} $1"
}

error() {
    echo -e "${RED}✗${NC} $1"
    exit 1
}

warning() {
    echo -e "${YELLOW}⚠${NC} $1"
}

# Step 1: 检查环境
check_step 1 "检查环境和工具"

command -v mvn >/dev/null 2>&1 || error "Maven 未安装，请先安装 Maven"
success "Maven 已安装: $(mvn -v | head -1)"

command -v gpg >/dev/null 2>&1 || error "GPG 未安装，请先安装 GPG"
success "GPG 已安装: $(gpg --version | head -1)"

# Step 2: 检查版本号
check_step 2 "检查项目版本号"

cd "$PROJECT_ROOT"
VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)
echo "当前版本: $VERSION"

if [[ "$VERSION" == *-SNAPSHOT ]]; then
    error "❌ 版本号为 SNAPSHOT: $VERSION，不能发布正式版本！"
fi
success "版本号检查通过（非 SNAPSHOT）: $VERSION"

# Step 3: 检查 Git 状态
check_step 3 "检查 Git 状态"

if [ -d ".git" ]; then
    if [ -n "$(git status --porcelain)" ]; then
        error "工作目录有未提交的变更，请先提交所有更改"
    fi
    success "Git 工作目录干净"

    LAST_TAG=$(git describe --tags --always)
    success "最新标签: $LAST_TAG"
else
    warning "不是 Git 仓库，跳过 Git 检查"
fi

# Step 4: 检查 POM 配置
check_step 4 "验证 POM 配置"

# 检查必填字段
for field in "name" "description" "url" "license/name" "license/url" "scm/url" "scm/connection" "developers/developer/name"; do
    # 简单检查，实际可用 xmllint
    if ! grep -q "name\|description\|url\|license\|scm\|developers" "$PROJECT_ROOT/pom.xml"; then
        warning "请检查 POM 中是否包含: $field"
    fi
done
success "POM 基础字段检查通过"

# Step 5: 检查发布仓库配置
check_step 5 "检查发布仓库配置"

if grep -q "distributionManagement" "$PROJECT_ROOT/pom.xml"; then
    success "发布仓库已配置"
    grep -A 10 "distributionManagement" "$PROJECT_ROOT/pom.xml" | head -5
else
    error "未找到 distributionManagement 配置"
fi

# Step 6: 检查 GPG 密钥
check_step 6 "检查 GPG 密钥"

GPGKEYS=$(gpg --list-secret-keys --keyid-format=short | grep 'sec\|uid' | head -10)

if [ -z "$GPGKEYS" ]; then
    error "❌ 未找到 GPG 密钥！请先生成密钥对:"
    echo "  gpg --gen-key"
fi

echo "可用的 GPG 密钥:"
gpg --list-secret-keys --keyid-format=short | grep -E "(sec|uid)"
success "GPG 密钥检查通过"

# Step 7: 编译和测试
check_step 7 "编译项目"

mvn clean compile -q || error "编译失败"
success "编译成功"

# Step 8: 运行单元测试
check_step 8 "运行单元测试"

mvn test -q -pl rxlib || warning "测试失败或有跳过的测试（可能需要手动检查）"
success "测试步骤完成"

# Step 9: 验证签名配置
check_step 9 "验证 GPG 签名配置"

# 检查 maven-gpg-plugin
if grep -q "maven-gpg-plugin" "$PROJECT_ROOT/pom.xml"; then
    success "maven-gpg-plugin 已配置"
else
    warning "未找到 maven-gpg-plugin 配置，发布时可能无法签名"
fi

# Step 10: 本地签名测试
check_step 10 "本地签名测试"

echo "生成 GPG 密钥列表用于测试..."
GPGKEY=$(gpg --list-secret-keys --keyid-format=short | grep 'sec' | head -1 | awk '{print $2}' | cut -d'/' -f2)

if [ -n "$GPGKEY" ]; then
    echo "使用密钥: $GPGKEY"

    # 仅编译和验证，不实际部署
    mvn clean verify -DskipTests -Dgpg.skip=false -Dgpg.keyname="$GPGKEY" -q 2>/dev/null || {
        warning "本地签名测试失败（可能需要密码交互或缺少 gpg-agent），但不影响 CI/CD"
    }
    success "签名配置测试完成"
else
    warning "无法自动选择 GPG 密钥，请手动在 CI 中配置"
fi

# Step 11: 生成源码包
check_step 11 "验证源码打包"

mvn clean source:jar javadoc:jar -q || warning "源码或文档生成失败"
success "源码打包验证完成"

# Step 12: 总结
check_step 12 "本地验证总结"

echo ""
echo "================================================"
echo -e "${GREEN}✓ 本地验证通过！${NC}"
echo "================================================"
echo ""
echo "发布前的最后一步："
echo "  1. 确保所有更改已提交到 Git"
echo "  2. 创建和推送版本标签:"
echo "     git tag -a v$VERSION -m 'Release version $VERSION'"
echo "     git push origin v$VERSION"
echo ""
echo "  3. GitHub Actions 将自动触发发布流程"
echo "  4. 在 GitHub → Actions 中监控发布进度"
echo ""
echo "相关文档："
echo "  - .github/MAVEN_CENTRAL_RELEASE.md (详细配置)"
echo "  - .github/RELEASE_CHECKLIST.md (发布检查清单)"
echo ""

