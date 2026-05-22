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

PORT=${PORT:-9900}
UDP2RAW_PORT=${UDP2RAW_PORT:-9910}
HTTP_SERVER_PORT=${HTTP_SERVER_PORT:-8082}
REQUIRED_TCP_PORTS=("${PORT}" "${HTTP_SERVER_PORT}")
# RSS server VPS 标称上下各 60Mbps GIA；默认按 Mbps(bit/s) 转 KiB/s 后取 98%。
GLOBAL_TRAFFIC_UPLOAD_KBPS=${GLOBAL_TRAFFIC_UPLOAD_KBPS:-7178}
GLOBAL_TRAFFIC_DOWNLOAD_KBPS=${GLOBAL_TRAFFIC_DOWNLOAD_KBPS:-7178}
GLOBAL_TRAFFIC_CHECK_INTERVAL_MILLIS=${GLOBAL_TRAFFIC_CHECK_INTERVAL_MILLIS:-1000}
GLOBAL_UDP_MAX_PENDING_BYTES=${GLOBAL_UDP_MAX_PENDING_BYTES:-131072}
GLOBAL_UDP_MAX_PENDING_PACKETS=${GLOBAL_UDP_MAX_PENDING_PACKETS:-256}
MEM_OPTIONS="-Xms256m -Xmx256m -Xss256k -XX:MaxMetaspaceSize=96m -XX:ReservedCodeCacheSize=96m -XX:MaxDirectMemorySize=640m -XX:+UseCompressedClassPointers"
GC_OPTIONS="-XX:+UseG1GC -XX:MaxGCPauseMillis=30 -XX:ParallelGCThreads=2 -XX:ConcGCThreads=1 -XX:G1ReservePercent=20 -XX:InitiatingHeapOccupancyPercent=30 -XX:+ParallelRefProcEnabled -XX:+AlwaysPreTouch -XX:+ExplicitGCInvokesConcurrent -XX:-OmitStackTraceInFastThrow"
APP_OPTIONS="-Dapp.net.reactorThreadAmount=2 -Dapp.net.connectTimeoutMillis=8000 -Dapp.net.dns.remoteServers=127.0.0.1:53 -Dapp.net.http.serverPort=${HTTP_SERVER_PORT} -Dapp.net.http.serverTls=true -Dapp.net.globalTraffic.enabled=true -Dapp.net.globalTraffic.uploadKilobytesPerSecond=${GLOBAL_TRAFFIC_UPLOAD_KBPS} -Dapp.net.globalTraffic.downloadKilobytesPerSecond=${GLOBAL_TRAFFIC_DOWNLOAD_KBPS} -Dapp.net.globalTraffic.checkIntervalMillis=${GLOBAL_TRAFFIC_CHECK_INTERVAL_MILLIS} -Dapp.net.globalTraffic.tcpBackpressureEnabled=true -Dapp.net.globalTraffic.udpBackpressureEnabled=true -Dapp.net.globalTraffic.udpMaxPendingBytes=${GLOBAL_UDP_MAX_PENDING_BYTES} -Dapp.net.globalTraffic.udpMaxPendingPackets=${GLOBAL_UDP_MAX_PENDING_PACKETS} -Dio.netty.allocator.type=pooled -Dio.netty.allocator.maxOrder=9 -Dio.netty.tryReflectionSetAccessible=true"
JDK21_MODULE_OPTS="--add-opens java.base/java.io=ALL-UNNAMED --add-opens java.base/java.net=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.util.concurrent=ALL-UNNAMED --add-opens java.base/java.util.concurrent.atomic=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED --add-opens java.base/jdk.internal.misc=ALL-UNNAMED"
DUMP_OPTS="-Xlog:gc*,gc+age=trace,safepoint:file=./gc.log:time,uptime:filecount=10,filesize=10M -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=./heapdump-$(date +%Y%m%d_%H%M%S).hprof -XX:ErrorFile=./hs_err_pid%p.log -XX:+CreateCoredumpOnCrash -XX:+ExitOnOutOfMemoryError --add-exports java.base/jdk.internal.ref=ALL-UNNAMED ${JDK21_MODULE_OPTS}"
BACKUP_PREFIX="app.jar.backup."
MAX_BACKUP_COUNT=5
JAVA_PROCESS_KEYWORD="app.jar -port=${PORT}"
APP_LOG_FILE="${APP_LOG_FILE:-./app.out}"

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

port_in_use() {
    local port="${1:-$PORT}"
    if command -v ss >/dev/null 2>&1; then
        ss -ltn 2>/dev/null | awk '{print $4}' | grep -Eq "(^|:)${port}$" && return 0
    fi
    if command -v netstat >/dev/null 2>&1; then
        netstat -ltn 2>/dev/null | awk '{print $4}' | grep -Eq "(^|:)${port}$" && return 0
    fi
    return 1
}

any_required_port_in_use() {
    local port
    for port in "${REQUIRED_TCP_PORTS[@]}"; do
        if port_in_use "${port}"; then
            return 0
        fi
    done
    return 1
}

print_required_port_status() {
    local port state
    for port in "${REQUIRED_TCP_PORTS[@]}"; do
        state="free"
        if port_in_use "${port}"; then
            state="in-use"
        fi
        echo "${YELLOW}[${LOCAL_TIME}] port ${port}/tcp ${state}"
    done
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

pid_alive() {
    local pid="$1"
    [ -n "${pid}" ] && kill -0 "${pid}" >/dev/null 2>&1
}

# 优先按端口杀进程，端口未绑定时再按命令行兜底，确保发布前旧进程已退出。
stop_old_process() {
    local wait_count

    if process_exists; then
        echo "${YELLOW}[${LOCAL_TIME}] 检测到残留进程，按命令行补充终止..."
        kill_by_pattern "-15"
    fi

    wait_count=0
    while [ ${wait_count} -lt 15 ]; do
        if ! any_required_port_in_use && ! process_exists; then
            echo "${GREEN}[${LOCAL_TIME}] 旧进程已完全退出"
            return 0
        fi
        sleep 1
        wait_count=$((wait_count + 1))
    done

    if process_exists || any_required_port_in_use; then
        echo "${YELLOW}[${LOCAL_TIME}] 旧进程未在超时内退出，执行强制终止..."
        kill_by_pattern "-9"
        sleep 1
    fi

    if process_exists || any_required_port_in_use; then
        echo "${RED}[${LOCAL_TIME}] 旧进程仍未退出，请检查权限或手动处理"
        print_required_port_status
        return 1
    fi
    echo "${GREEN}[${LOCAL_TIME}] 旧进程已强制终止"
    return 0
}

# 启动后等待端口真正绑定，避免 JVM 已启动但服务尚未完成初始化时误判失败。
wait_for_startup() {
    local wait_count pid

    pid="${1:-}"
    wait_count=0
    while [ ${wait_count} -lt 30 ]; do
        if [ -n "${pid}" ]; then
            if ! pid_alive "${pid}"; then
                echo "${RED}[${LOCAL_TIME}] 启动失败！进程已退出，请手动查看 ${APP_LOG_FILE}"
                return 1
            fi
        elif ! process_exists; then
            echo "${RED}[${LOCAL_TIME}] 启动失败！进程已退出，请手动执行查看错误"
            return 1
        fi

        if port_in_use "${PORT}" && port_in_use "${HTTP_SERVER_PORT}"; then
            PID="${pid:-$(get_process_pid)}"
            echo "${GREEN}[${LOCAL_TIME}] 启动成功！PID: ${PID}，端口 ${PORT}/tcp 与 ${HTTP_SERVER_PORT}/tcp 均已绑定"
            return 0
        fi

        if [ ${wait_count} -eq 0 ]; then
            echo "${YELLOW}[${LOCAL_TIME}] 进程已启动，等待端口 ${PORT}/tcp 与 ${HTTP_SERVER_PORT}/tcp 完成绑定... PID: ${pid}"
        fi
        sleep 1
        wait_count=$((wait_count + 1))
    done

    pid="${pid:-$(get_process_pid)}"
    echo "${RED}[${LOCAL_TIME}] 启动超时！进程仍在运行但必需端口未全部绑定，PID: ${pid}"
    print_required_port_status
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
    echo "${RED}[${LOCAL_TIME}] 发布模式：正在终止端口 ${PORT}/tcp 与 ${HTTP_SERVER_PORT}/tcp 的旧进程..."
    stop_old_process || exit 1

    if [ -f "app.jar.publish" ]; then
        rotate_latest_jar
        if [ -f "app.jar" ]; then
            mv "app.jar" "app.jar.latest"
        fi
        mv "app.jar.publish" "app.jar"
    fi
elif [ "$ACTION" = "start" ]; then
    echo "${RED}[${LOCAL_TIME}] 启动模式：正在检测端口 ${PORT}/tcp 与 ${HTTP_SERVER_PORT}/tcp 的进程..."
    if any_required_port_in_use || process_exists; then
        PID=$(get_process_pid)
        print_required_port_status
        if [ -n "${PID}" ]; then
            echo "${GREEN}[${LOCAL_TIME}] ${PORT}/tcp 已运行，PID: ${PID}"
            exit 0
        fi
        echo "${RED}[${LOCAL_TIME}] 必需端口被占用，但未找到匹配进程，请手动检查 ${HTTP_SERVER_PORT}/${PORT} 占用者"
        exit 1
    fi
else
    echo "错误：无效参数 '$ACTION'"
    usage
fi

echo "${YELLOW}[${LOCAL_TIME}] 正在启动 ${PORT}/tcp 的进程，HttpServer 端口 ${HTTP_SERVER_PORT}/tcp..."
UDP2RAW_ARG=""
if [ -n "${UDP2RAW_PORT}" ]; then
  UDP2RAW_ARG="-udp2rawPort=${UDP2RAW_PORT}"
fi
nohup java ${MEM_OPTIONS} ${GC_OPTIONS} ${APP_OPTIONS} ${DUMP_OPTS} -Dfile.encoding=UTF-8 -jar app.jar -port=${PORT} -shadowMode=1 ${UDP2RAW_ARG} -debug=0 "-shadowUser=youfanX:5PXx0^JNMOgvn3P658@f-li.cn:9900" >>"${APP_LOG_FILE}" 2>&1 &
APP_PID=$!
wait_for_startup "${APP_PID}" || exit 1
