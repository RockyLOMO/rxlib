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

PORT=9900
MEM_OPTIONS="-Xms64m -Xmx128m -Xss256k -XX:MaxMetaspaceSize=64m -XX:MaxDirectMemorySize=640m -XX:+UseG1GC -XX:MaxGCPauseMillis=50 -XX:+UseCompressedClassPointers -XX:+UseStringDeduplication"
APP_OPTIONS="-Dapp.net.reactorThreadAmount=2 -Dapp.net.connectTimeoutMillis=8000 -Dio.netty.allocator.type=pooled -Dio.netty.allocator.maxOrder=9 -Dapp.net.dns.outlandServers=127.0.0.1:53,1.1.1.1:53"
DUMP_OPTS="-Xlog:gc*,gc+age=trace,safepoint:file=./gc.log:time,uptime:filecount=10,filesize=10M -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=./heapdump-$(date +%Y%m%d_%H%M%S).hprof -XX:ErrorFile=./hs_err_pid%p.log -XX:+CreateCoredumpOnCrash"
BACKUP_PREFIX="app.jar.backup."
MAX_BACKUP_COUNT=5
JAVA_PROCESS_KEYWORD="app.jar -port=${PORT}"

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

# 兼容不同环境的 fuser 路径。
get_fuser_cmd() {
    if [ -x "/usr/sbin/fuser" ]; then
        echo "/usr/sbin/fuser"
        return 0
    fi
    if command -v fuser >/dev/null 2>&1; then
        command -v fuser
        return 0
    fi
    return 1
}

# 非交互场景优先直接执行，只有在允许免密 sudo 时才尝试 sudo -n。
run_fuser_kill() {
    local signal_arg="${1:-}"
    local fuser_cmd
    fuser_cmd=$(get_fuser_cmd) || return 0

    "${fuser_cmd}" ${signal_arg} ${PORT}/tcp >/dev/null 2>&1 && return 0
    sudo -n "${fuser_cmd}" ${signal_arg} ${PORT}/tcp >/dev/null 2>&1 && return 0
    return 0
}

port_in_use() {
    local fuser_cmd
    fuser_cmd=$(get_fuser_cmd) || return 1
    "${fuser_cmd}" ${PORT}/tcp >/dev/null 2>&1
}

kill_by_pattern() {
    local signal="$1"
    local pid_list pid

    pid_list=$(pgrep -f "${JAVA_PROCESS_KEYWORD}" 2>/dev/null)
    [ -z "${pid_list}" ] && return 0

    while IFS= read -r pid; do
        if [ -n "${pid}" ]; then
            kill "${signal}" "${pid}" >/dev/null 2>&1 || true
        fi
    done <<EOF
${pid_list}
EOF
}

process_exists() {
    pgrep -f "${JAVA_PROCESS_KEYWORD}" >/dev/null 2>&1
}

get_process_pid() {
    pgrep -f "${JAVA_PROCESS_KEYWORD}" | head -1
}

# 优先按端口杀进程，端口未绑定时再按命令行兜底，确保发布前旧进程已退出。
stop_old_process() {
    local wait_count

    run_fuser_kill "-k"
    if process_exists; then
        echo "${YELLOW}[${LOCAL_TIME}] 检测到残留进程，按命令行补充终止..."
        kill_by_pattern "-15"
    fi

    wait_count=0
    while [ ${wait_count} -lt 15 ]; do
        if ! port_in_use && ! process_exists; then
            echo "${GREEN}[${LOCAL_TIME}] 旧进程已完全退出"
            return 0
        fi
        sleep 1
        wait_count=$((wait_count + 1))
    done

    if process_exists || port_in_use; then
        echo "${YELLOW}[${LOCAL_TIME}] 旧进程未在超时内退出，执行强制终止..."
        kill_by_pattern "-9"
        run_fuser_kill "-k -9"
        sleep 1
    fi

    if process_exists || port_in_use; then
        echo "${RED}[${LOCAL_TIME}] 旧进程仍未退出，请检查权限或手动处理"
        return 1
    fi
    echo "${GREEN}[${LOCAL_TIME}] 旧进程已强制终止"
    return 0
}

# 启动后等待端口真正绑定，避免 JVM 已启动但服务尚未完成初始化时误判失败。
wait_for_startup() {
    local wait_count pid

    wait_count=0
    while [ ${wait_count} -lt 30 ]; do
        if port_in_use; then
            PID=$(get_fuser_cmd | xargs -I{} sh -c "'{}' ${PORT}/tcp 2>/dev/null" | awk '{print $1}' | head -1)
            echo "${GREEN}[${LOCAL_TIME}] 启动成功！PID: ${PID}"
            return 0
        fi

        if ! process_exists; then
            echo "${RED}[${LOCAL_TIME}] 启动失败！进程已退出，请手动执行查看错误"
            return 1
        fi

        if [ ${wait_count} -eq 0 ]; then
            pid=$(get_process_pid)
            echo "${YELLOW}[${LOCAL_TIME}] 进程已启动，等待端口 ${PORT}/tcp 完成绑定... PID: ${pid}"
        fi
        sleep 1
        wait_count=$((wait_count + 1))
    done

    pid=$(get_process_pid)
    echo "${RED}[${LOCAL_TIME}] 启动超时！进程仍在运行但端口 ${PORT}/tcp 未完成绑定，PID: ${pid}"
    return 1
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
    stop_old_process || exit 1

    if [ -f "app.jar.publish" ]; then
        rotate_latest_jar
        if [ -f "app.jar" ]; then
            mv "app.jar" "app.jar.latest"
        fi
        mv "app.jar.publish" "app.jar"
    fi
elif [ "$ACTION" = "start" ]; then
    echo "${RED}[${LOCAL_TIME}] 启动模式：正在检测端口 ${PORT}/tcp 的进程..."
    if port_in_use; then
        PID=$(get_fuser_cmd | xargs -I{} sh -c "'{}' ${PORT}/tcp 2>/dev/null" | awk '{print $1}' | head -1)
        echo "${GREEN}[${LOCAL_TIME}] ${PORT}/tcp 已运行，PID: ${PID}"
        exit 0
    fi
    # 增加进程检测，防止端口未绑定时的重复启动（解决 Cron 与 SSH 并发/启动间隙问题）
    # pgrep -f 匹配完整命令行
    PID_P=$(pgrep -f "app.jar -port=${PORT}" | head -1)
    if [ -n "$PID_P" ]; then
        echo "${GREEN}[${LOCAL_TIME}] 进程已存在(正在启动中)，PID: ${PID_P}"
        exit 0
    fi
else
    echo "错误：无效参数 '$ACTION'"
    usage
fi

echo "${YELLOW}[${LOCAL_TIME}] 正在启动 ${PORT}/tcp 的进程..."
nohup java ${MEM_OPTIONS} ${APP_OPTIONS} ${DUMP_OPTS} -Dfile.encoding=UTF-8 -jar app.jar -port=${PORT} -shadowMode=1 -udp2raw=1 -debug=1 "-shadowUser=youfanX:5PXx0^JNMOgvn3P658@f-li.cn:9900" >/dev/null 2>&1 &
wait_for_startup || exit 1
