#!/bin/bash

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
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" &>/dev/null && pwd)
cd "${SCRIPT_DIR}" || exit 1

PORT=${PORT:-6885}
HTTP_SERVER_PORT=${HTTP_SERVER_PORT:-8082}
REQUIRED_TCP_PORTS=("${PORT}" "${HTTP_SERVER_PORT}")
REUSE_PORT_BIND_COUNT=${REUSE_PORT_BIND_COUNT:-2}
STARTUP_WAIT_SECONDS=${STARTUP_WAIT_SECONDS:-45}
MIN_STARTUP_ALIVE_SECONDS=${MIN_STARTUP_ALIVE_SECONDS:-8}
DRAIN_TIMEOUT_SECONDS=${DRAIN_TIMEOUT_SECONDS:-180}
# 首次迁移时，旧二进制未开启 SO_REUSEPORT，新进程无法同端口绑定；允许一次兼容重启。
REUSEPORT_MIGRATION_FALLBACK=${REUSEPORT_MIGRATION_FALLBACK:-1}
DEPLOY_ID=${DEPLOY_ID:-$(date +%Y%m%d_%H%M%S)_$$}

MEM_OPTIONS="-Xms2g -Xmx2g -Xss512k -XX:MaxMetaspaceSize=192m -XX:MaxDirectMemorySize=3g -XX:+UseCompressedClassPointers"
GC_OPTIONS="-XX:+UseG1GC -XX:MaxGCPauseMillis=50 -XX:+ParallelRefProcEnabled -XX:+AlwaysPreTouch -XX:+UseStringDeduplication -XX:+ExplicitGCInvokesConcurrent -XX:-OmitStackTraceInFastThrow"
APP_OPTIONS="-Dapp.net.reactorThreadAmount=10 -Dapp.net.reusePortBindCount=${REUSE_PORT_BIND_COUNT} -Dapp.rss.drainMaxWaitMillis=$((DRAIN_TIMEOUT_SECONDS * 1000)) -Dapp.net.connectTimeoutMillis=10000 -Dapp.net.dns.inlandServers=192.168.31.1:53 -Dapp.net.http.serverPort=${HTTP_SERVER_PORT} -Dapp.net.http.serverTls=false -Dapp.storage.h2Settings=CACHE_SIZE=16384;MAX_MEMORY_ROWS=4096;MAX_OPERATION_MEMORY=16384;WRITE_DELAY=200;AUTO_SERVER=TRUE -Dapp.storage.h2MaxConnections=6 -Dapp.diagnostic.h2Settings=CACHE_SIZE=4096;MAX_MEMORY_ROWS=1024;MAX_OPERATION_MEMORY=4096;WRITE_DELAY=1000;AUTO_SERVER=TRUE -Dapp.diagnostic.h2MaxConnections=2 -Dio.netty.allocator.type=pooled -Dio.netty.allocator.maxOrder=9 -Dio.netty.tryReflectionSetAccessible=true"
JDK17_MODULE_OPTS="--add-opens java.base/java.io=ALL-UNNAMED --add-opens java.base/java.net=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.util.concurrent=ALL-UNNAMED --add-opens java.base/java.util.concurrent.atomic=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED --add-opens java.base/jdk.internal.misc=ALL-UNNAMED"
DUMP_OPTS="-Xlog:gc*,gc+age=trace,safepoint:file=./gc.log:time,uptime:filecount=10,filesize=10M -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=${SCRIPT_DIR}/ -XX:ErrorFile=${SCRIPT_DIR}/hs_err_pid%p.log -XX:+CreateCoredumpOnCrash -XX:+ExitOnOutOfMemoryError --add-exports java.base/jdk.internal.ref=ALL-UNNAMED ${JDK17_MODULE_OPTS}"
BACKUP_PREFIX="app.jar.backup."
MAX_BACKUP_COUNT=5
JAVA_PROCESS_KEYWORD="app.jar -port=${PORT}"

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

rotate_latest_jar() {
    local backup_file
    if [ ! -f "app.jar.latest" ]; then
        return 0
    fi

    backup_file=$(next_backup_file)
    mv "app.jar.latest" "${backup_file}"
    echo "${YELLOW}[${LOCAL_TIME}] 已归档历史 latest -> ${backup_file}${NC}"
    cleanup_backup_jars
}

archive_current_jar() {
    local backup_file
    if [ ! -f "app.jar" ]; then
        return 0
    fi

    backup_file=$(next_backup_file)
    mv "app.jar" "${backup_file}"
    echo "${YELLOW}[${LOCAL_TIME}] 已归档当前 jar -> ${backup_file}${NC}"
    cleanup_backup_jars
}

restore_latest_jar() {
    if [ ! -f "app.jar.latest" ]; then
        echo "${RED}[${LOCAL_TIME}] 未找到 app.jar.latest，无法回滚${NC}"
        return 1
    fi

    archive_current_jar
    mv "app.jar.latest" "app.jar"
    echo "${GREEN}[${LOCAL_TIME}] 已恢复 app.jar.latest -> app.jar${NC}"
}

process_pids() {
    pgrep -f "${JAVA_PROCESS_KEYWORD}" 2>/dev/null || true
}

deploy_pids() {
    pgrep -f "app.deploy.id=${DEPLOY_ID}" 2>/dev/null || true
}

pid_alive() {
    local pid="$1"
    [ -n "${pid}" ] && kill -0 "${pid}" >/dev/null 2>&1
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

pid_listens_on() {
    local pid="$1"
    local port="$2"
    if ! command -v ss >/dev/null 2>&1; then
        return 1
    fi
    ss -ltnp 2>/dev/null | grep -F "pid=${pid}," | awk '{print $4}' | grep -Eq "(^|:)${port}$"
}

pid_listens_required_ports() {
    local pid="$1"
    local port
    for port in "${REQUIRED_TCP_PORTS[@]}"; do
        pid_listens_on "${pid}" "${port}" || return 1
    done
    return 0
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
        echo "${YELLOW}[${LOCAL_TIME}] port ${port}/tcp ${state}${NC}"
    done
}

kill_by_pattern() {
    local signal="$1"
    local pid_list pid

    pid_list=$(process_pids)
    [ -z "${pid_list}" ] && return 0

    while IFS= read -r pid; do
        [ -n "${pid}" ] && kill "${signal}" "${pid}" >/dev/null 2>&1 || true
    done <<EOF
${pid_list}
EOF
}

process_exists() {
    [ -n "$(process_pids)" ]
}

get_process_pid() {
    process_pids | head -1
}

stop_old_process() {
    local wait_count

    if process_exists; then
        echo "${YELLOW}[${LOCAL_TIME}] 检测到残留进程，按命令行补充终止...${NC}"
        kill_by_pattern "-15"
    fi

    wait_count=0
    while [ ${wait_count} -lt 15 ]; do
        if ! any_required_port_in_use && ! process_exists; then
            echo "${GREEN}[${LOCAL_TIME}] 旧进程已完全退出${NC}"
            return 0
        fi
        sleep 1
        wait_count=$((wait_count + 1))
    done

    if process_exists || any_required_port_in_use; then
        echo "${YELLOW}[${LOCAL_TIME}] 旧进程未在超时内退出，执行强制终止...${NC}"
        kill_by_pattern "-9"
        sleep 1
    fi

    if process_exists || any_required_port_in_use; then
        echo "${RED}[${LOCAL_TIME}] 旧进程仍未退出，请检查权限或手动处理${NC}"
        print_required_port_status
        return 1
    fi
    echo "${GREEN}[${LOCAL_TIME}] 旧进程已强制终止${NC}"
    return 0
}

start_new_process() {
    local log_file
    log_file="app-${DEPLOY_ID}.out"
    echo "${YELLOW}[${LOCAL_TIME}] 正在启动新进程 deployId=${DEPLOY_ID}, log=${log_file}${NC}"
    nohup java ${MEM_OPTIONS} ${GC_OPTIONS} ${APP_OPTIONS} ${DUMP_OPTS} -Dapp.deploy.id="${DEPLOY_ID}" -Dfile.encoding=UTF-8 -jar app.jar -port=${PORT} -udp2raw=1 >"${log_file}" 2>&1 &
}

wait_for_new_process() {
    local wait_count pid

    wait_count=0
    NEW_PID=""
    while [ ${wait_count} -lt ${STARTUP_WAIT_SECONDS} ]; do
        pid=$(deploy_pids | head -1)
        if pid_alive "${pid}"; then
            if pid_listens_required_ports "${pid}"; then
                echo "${GREEN}[${LOCAL_TIME}] 新进程已绑定必需端口，PID: ${pid}${NC}"
                NEW_PID="${pid}"
                return 0
            fi
            if [ ${wait_count} -ge ${MIN_STARTUP_ALIVE_SECONDS} ]; then
                echo "${GREEN}[${LOCAL_TIME}] 新进程存活，PID: ${pid}；当前环境无法精确校验监听 PID，继续发布${NC}"
                NEW_PID="${pid}"
                return 0
            fi
        fi
        sleep 1
        wait_count=$((wait_count + 1))
    done

    echo "${RED}[${LOCAL_TIME}] 新进程启动超时或已退出 deployId=${DEPLOY_ID}${NC}"
    if [ -f "app-${DEPLOY_ID}.out" ]; then
        tail -n 80 "app-${DEPLOY_ID}.out" || true
    fi
    return 1
}

last_start_looks_first_migration_conflict() {
    local log_file
    log_file="app-${DEPLOY_ID}.out"
    [ -f "${log_file}" ] || return 1
    grep -Eiq "Address already in use|BindException|EADDRINUSE|bind .*fail|bind .*failed|Database may be already in use|database is already in use|Locked by another process|File lock|JdbcSQLNonTransientConnectionException" "${log_file}"
}

signal_drain_old_processes() {
    local old_pids="$1"
    local new_pid="$2"
    local pid signaled
    signaled=0
    [ -z "${old_pids}" ] && return 0

    while IFS= read -r pid; do
        if [ -z "${pid}" ] || [ "${pid}" = "${new_pid}" ]; then
            continue
        fi
        if pid_alive "${pid}"; then
            echo "${YELLOW}[${LOCAL_TIME}] 通知旧进程进入 drain，PID: ${pid}${NC}"
            kill -USR2 "${pid}" >/dev/null 2>&1 || kill -15 "${pid}" >/dev/null 2>&1 || true
            signaled=$((signaled + 1))
        fi
    done <<EOF
${old_pids}
EOF

    if [ ${signaled} -gt 0 ]; then
        echo "${GREEN}[${LOCAL_TIME}] 已通知 ${signaled} 个旧进程 drain；最长保留 ${DRAIN_TIMEOUT_SECONDS}s 后由 JVM 自行退出${NC}"
    fi
}

publish_jar() {
    if [ ! -f "app.jar.publish" ]; then
        echo "${YELLOW}[${LOCAL_TIME}] 未找到 app.jar.publish，仅执行启动/切换逻辑${NC}"
        return 0
    fi

    rotate_latest_jar
    if [ -f "app.jar" ]; then
        mv "app.jar" "app.jar.latest"
    fi
    mv "app.jar.publish" "app.jar"
    sleep 1
}

publish_with_reuseport() {
    local old_pids new_pid
    old_pids=$(process_pids)
    publish_jar || return 1
    start_new_process
    wait_for_new_process || return 1
    new_pid="${NEW_PID}"
    signal_drain_old_processes "${old_pids}" "${new_pid}"
    echo "${GREEN}[${LOCAL_TIME}] 发布完成，新进程 PID: ${new_pid}${NC}"
}

publish_with_legacy_restart() {
    echo "${YELLOW}[${LOCAL_TIME}] 同端口并行启动失败，执行首次迁移兼容重启${NC}"
    stop_old_process || return 1
    DEPLOY_ID="${DEPLOY_ID}_legacy"
    start_new_process
    wait_for_new_process || return 1
}

start_if_absent() {
    local pid
    if any_required_port_in_use || process_exists; then
        pid=$(get_process_pid)
        print_required_port_status
        if [ -n "${pid}" ]; then
            echo "${GREEN}[${LOCAL_TIME}] ${PORT}/tcp 已运行，PID: ${pid}${NC}"
            return 0
        fi
        echo "${RED}[${LOCAL_TIME}] 必需端口被占用，但未找到匹配进程，请手动检查占用者${NC}"
        return 1
    fi

    start_new_process
    wait_for_new_process
}

rollback_release() {
    local old_pids new_pid
    old_pids=$(process_pids)
    restore_latest_jar || return 1
    start_new_process
    wait_for_new_process || return 1
    new_pid="${NEW_PID}"
    signal_drain_old_processes "${old_pids}" "${new_pid}"
    echo "${GREEN}[${LOCAL_TIME}] 回滚完成，新进程 PID: ${new_pid}${NC}"
}

usage() {
    echo "用法: $0 [publish|start|rollback]"
    echo "  publish  : 发布模式，优先 SO_REUSEPORT 新旧进程并行切换"
    echo "  start    : 启动模式，不存在则启动"
    echo "  rollback : 回滚到 app.jar.latest，并并行切换"
    exit 1
}

if [ $# -ne 1 ]; then
    usage
fi

ACTION="$1"
case "${ACTION}" in
    publish)
        echo "${YELLOW}[${LOCAL_TIME}] 发布模式：SO_REUSEPORT=${REUSE_PORT_BIND_COUNT}, drain=${DRAIN_TIMEOUT_SECONDS}s${NC}"
        if ! publish_with_reuseport; then
            if [ "${REUSEPORT_MIGRATION_FALLBACK}" = "1" ] && last_start_looks_first_migration_conflict; then
                publish_with_legacy_restart || exit 1
            else
                exit 1
            fi
        fi
        ;;
    start)
        echo "${YELLOW}[${LOCAL_TIME}] 启动模式：检测端口 ${PORT}/tcp 与 ${HTTP_SERVER_PORT}/tcp${NC}"
        start_if_absent || exit 1
        ;;
    rollback)
        echo "${YELLOW}[${LOCAL_TIME}] 回滚模式：恢复 app.jar.latest 并切换${NC}"
        rollback_release || exit 1
        ;;
    *)
        echo "错误：无效参数 '${ACTION}'"
        usage
        ;;
esac
