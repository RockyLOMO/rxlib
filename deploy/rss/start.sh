#!/bin/bash
# 颜色输出
if [[ -t 1 ]]; then
  RED='\033[0;31m'
  GREEN='\033[0;32m'
  YELLOW='\033[1;33m'
  NC='\033[0m'
else
  RED=''
  GREEN=''
  YELLOW=''
  NC=''
fi
LOCAL_TIME=$(date +"%Y-%m-%d %H:%M:%S")
# 获取当前脚本所在目录
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" &>/dev/null && pwd)
cd $SCRIPT_DIR

PORT=6885
MEM_OPTIONS="-Xms512m -Xmx1g -Xss512k -XX:MaxMetaspaceSize=128m -XX:MaxDirectMemorySize=2g -XX:-OmitStackTraceInFastThrow -XX:+UseCompressedClassPointers -XX:+UseStringDeduplication"
APP_OPTIONS="-Dapp.net.reactorThreadAmount=10 -Dapp.net.connectTimeoutMillis=10000 -Dapp.net.dns.inlandServers=192.168.31.1:53"
DUMP_OPTS="-Xlog:gc*,gc+age=trace,safepoint:file=./gc.log:time,uptime:filecount=10,filesize=10M -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=$SCRIPT_DIR/ -XX:ErrorFile=$SCRIPT_DIR/hs_err_pid%p.log -XX:+CreateCoredumpOnCrash -XX:+ExitOnOutOfMemoryError --add-exports java.base/jdk.internal.ref=ALL-UNNAMED"
BACKUP_PREFIX="app.jar.backup."
MAX_BACKUP_COUNT=5

# 生成不会冲突的历史 jar 名称。
next_backup_file() {
    local ts index backup_file
    ts=$(date +%Y%m%d_%H%M%S)
    index=0
    while true; do
        backup_file="${BACKUP_PREFIX}${ts}"
        if [ ${index} -gt 0 ]; then
            backup_file="${backup_file}_${index}"
        fi
        backup_file="${backup_file}.jar"
        if [ ! -e "${backup_file}" ]; then
            echo "${backup_file}"
            return 0
        fi
        index=$((index + 1))
    done
}

# 只保留最近 5 个历史 jar，避免归档无限增长。
cleanup_backup_jars() {
    local backup_files remove_count
    shopt -s nullglob
    backup_files=( ${BACKUP_PREFIX}*.jar )
    shopt -u nullglob
    if [ ${#backup_files[@]} -le ${MAX_BACKUP_COUNT} ]; then
        return 0
    fi

    remove_count=$((${#backup_files[@]} - MAX_BACKUP_COUNT))
    printf '%s\n' "${backup_files[@]}" | sort | head -n "${remove_count}" | while IFS= read -r old_file; do
        rm -f "${old_file}"
    done
}

# 发布前先把旧的 latest 归档，再保留当前包为 latest。
rotate_latest_jar() {
    local backup_file
    if [ ! -f "app.jar.latest" ]; then
        return 0
    fi

    backup_file=$(next_backup_file)
    mv "app.jar.latest" "${backup_file}"
    echo "${YELLOW}[${LOCAL_TIME}] 已归档历史 latest -> ${backup_file}"
    cleanup_backup_jars
}

# 优先按端口杀进程，端口未绑定时再按命令行兜底，确保发布前旧进程已退出。
stop_old_process() {
    local pid_list pid wait_count

    sudo fuser -k ${PORT}/tcp >/dev/null 2>&1 || true

    pid_list=$(pgrep -f "java .*app.jar .* -port=${PORT}" 2>/dev/null)
    if [ -n "${pid_list}" ]; then
        echo "${YELLOW}[${LOCAL_TIME}] 检测到残留进程，按命令行补充终止..."
        while IFS= read -r pid; do
            [ -n "${pid}" ] && kill "${pid}" >/dev/null 2>&1 || true
        done <<EOF
${pid_list}
EOF
    fi

    wait_count=0
    while [ ${wait_count} -lt 10 ]; do
        if ! fuser ${PORT}/tcp >/dev/null 2>&1 && ! pgrep -f "java .*app.jar .* -port=${PORT}" >/dev/null 2>&1; then
            return 0
        fi
        sleep 1
        wait_count=$((wait_count + 1))
    done

    pid_list=$(pgrep -f "java .*app.jar .* -port=${PORT}" 2>/dev/null)
    if [ -n "${pid_list}" ]; then
        echo "${YELLOW}[${LOCAL_TIME}] 旧进程未在超时内退出，执行强制终止..."
        while IFS= read -r pid; do
            [ -n "${pid}" ] && kill -9 "${pid}" >/dev/null 2>&1 || true
        done <<EOF
${pid_list}
EOF
    fi
    sudo fuser -k -9 ${PORT}/tcp >/dev/null 2>&1 || true
    sleep 1
}

# 用法提示
usage() {
    echo "用法: $0 [publish|start]"
    echo "  publish : 发布模式（会先终止端口 ${PORT} 的旧进程，然后启动）"
    echo "  start   : 启动模式（不终止端口 ${PORT} 的进程，不存在则启动）"
    exit 1
}
# 检查是否提供参数
if [ $# -ne 1 ]; then
    usage
fi
ACTION="$1"

# 根据参数决定是否 kill 端口
if [ "$ACTION" = "publish" ]; then
    echo "${RED}[${LOCAL_TIME}] 发布模式：正在终止端口 ${PORT}/tcp 的旧进程..."
    stop_old_process

    if [ -f "app.jar.publish" ]; then
        rotate_latest_jar
        if [ -f "app.jar" ]; then
            mv "app.jar" "app.jar.latest"
        fi
        mv "app.jar.publish" "app.jar"
        sleep 1
    fi
elif [ "$ACTION" = "start" ]; then
    echo "${RED}[${LOCAL_TIME}] 启动模式：正在检测端口 ${PORT}/tcp 的进程..."
    if fuser ${PORT}/tcp >/dev/null 2>&1; then
        PID=$(fuser ${PORT}/tcp 2>/dev/null | awk '{print $1}' | head -1)
        echo "${GREEN}[${LOCAL_TIME}] ${PORT}/tcp 已运行，PID: ${PID}"
        exit 0
    fi
else
    echo "错误：无效参数 '$ACTION'"
    usage
fi

echo "${YELLOW}[${LOCAL_TIME}] 正在启动 ${PORT}/tcp 的进程..."
nohup java ${MEM_OPTIONS} ${APP_OPTIONS} ${DUMP_OPTS} -Dfile.encoding=UTF-8 -jar app.jar -port=${PORT} -udp2raw=1 >/dev/null 2>&1 &
sleep 5

if fuser ${PORT}/tcp >/dev/null 2>&1; then
    PID=$(fuser ${PORT}/tcp 2>/dev/null | awk '{print $1}' | head -1)
    echo "${GREEN}[${LOCAL_TIME}] 启动成功！PID: ${PID}"
else
    echo "${RED}[${LOCAL_TIME}] 启动失败！请手动执行查看错误"
    exit 1
fi
