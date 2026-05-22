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
DRAIN_TIMEOUT_SECONDS=${DRAIN_TIMEOUT_SECONDS:-180}
DRAIN_TOKEN_DIR=${DRAIN_TOKEN_DIR:-.drain}
DRAIN_TOKEN_TTL_MILLIS=${DRAIN_TOKEN_TTL_MILLIS:-120000}
MAX_LIVE_PROCESSES=${MAX_LIVE_PROCESSES:-2}
DEPLOY_MODE=${DEPLOY_MODE:-replace}
DEPLOY_ID=${DEPLOY_ID:-$(date +%Y%m%d_%H%M%S)_$$}
# 明确打开才允许旧式重启；默认绝不因新进程失败而杀掉唯一旧实例。
REUSEPORT_MIGRATION_FALLBACK=${REUSEPORT_MIGRATION_FALLBACK:-0}
ROLLBACK_ON_LEGACY_FALLBACK_FAILURE=${ROLLBACK_ON_LEGACY_FALLBACK_FAILURE:-1}
# RSS client VM 标称 40Mbps 上行 / 200Mbps 下行；默认按 Mbps(bit/s) 转 KiB/s 后取 98%。
GLOBAL_TRAFFIC_UPLOAD_KBPS=${GLOBAL_TRAFFIC_UPLOAD_KBPS:-4785}
GLOBAL_TRAFFIC_DOWNLOAD_KBPS=${GLOBAL_TRAFFIC_DOWNLOAD_KBPS:-23926}
GLOBAL_TRAFFIC_CHECK_INTERVAL_MILLIS=${GLOBAL_TRAFFIC_CHECK_INTERVAL_MILLIS:-1000}
GLOBAL_UDP_MAX_PENDING_BYTES=${GLOBAL_UDP_MAX_PENDING_BYTES:-262144}
GLOBAL_UDP_MAX_PENDING_PACKETS=${GLOBAL_UDP_MAX_PENDING_PACKETS:-512}
DEPLOY_SLOTS=(a b)
if ! [[ "${MAX_LIVE_PROCESSES}" =~ ^[0-9]+$ ]] || [ "${MAX_LIVE_PROCESSES}" -lt 1 ]; then
    MAX_LIVE_PROCESSES=2
fi

MEM_OPTIONS="-Xms2g -Xmx2g -Xss512k -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=160m -XX:MaxDirectMemorySize=3g -XX:+UseCompressedClassPointers"
GC_OPTIONS="-XX:+UseG1GC -XX:MaxGCPauseMillis=30 -XX:ParallelGCThreads=4 -XX:ConcGCThreads=2 -XX:G1ReservePercent=20 -XX:InitiatingHeapOccupancyPercent=30 -XX:+ParallelRefProcEnabled -XX:+AlwaysPreTouch -XX:+ExplicitGCInvokesConcurrent -XX:-OmitStackTraceInFastThrow"
APP_OPTIONS="-Dapp.net.reactorThreadAmount=10 -Dapp.net.reusePortBindCount=${REUSE_PORT_BIND_COUNT} -Dapp.rss.drainMaxWaitMillis=$((DRAIN_TIMEOUT_SECONDS * 1000)) -Dapp.rss.drainTokenDir=${DRAIN_TOKEN_DIR} -Dapp.rss.drainTokenTtlMillis=${DRAIN_TOKEN_TTL_MILLIS} -Dapp.net.connectTimeoutMillis=10000 -Dapp.net.dns.directServers=192.168.31.1:53 -Dapp.net.http.serverPort=${HTTP_SERVER_PORT} -Dapp.net.http.serverTls=false -Dapp.net.globalTraffic.enabled=true -Dapp.net.globalTraffic.uploadKilobytesPerSecond=${GLOBAL_TRAFFIC_UPLOAD_KBPS} -Dapp.net.globalTraffic.downloadKilobytesPerSecond=${GLOBAL_TRAFFIC_DOWNLOAD_KBPS} -Dapp.net.globalTraffic.checkIntervalMillis=${GLOBAL_TRAFFIC_CHECK_INTERVAL_MILLIS} -Dapp.net.globalTraffic.tcpBackpressureEnabled=true -Dapp.net.globalTraffic.udpBackpressureEnabled=true -Dapp.net.globalTraffic.udpMaxPendingBytes=${GLOBAL_UDP_MAX_PENDING_BYTES} -Dapp.net.globalTraffic.udpMaxPendingPackets=${GLOBAL_UDP_MAX_PENDING_PACKETS} -Dapp.storage.h2Settings=CACHE_SIZE=16384;MAX_MEMORY_ROWS=4096;MAX_OPERATION_MEMORY=16384;WRITE_DELAY=200;AUTO_SERVER=TRUE -Dapp.storage.h2MaxConnections=6 -Dapp.diagnostic.h2Settings=CACHE_SIZE=4096;MAX_MEMORY_ROWS=1024;MAX_OPERATION_MEMORY=4096;WRITE_DELAY=1000;AUTO_SERVER=TRUE -Dapp.diagnostic.h2MaxConnections=2 -Dio.netty.allocator.type=pooled -Dio.netty.allocator.maxOrder=9 -Dio.netty.tryReflectionSetAccessible=true"
JDK21_MODULE_OPTS="--add-opens java.base/java.io=ALL-UNNAMED --add-opens java.base/java.net=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.util.concurrent=ALL-UNNAMED --add-opens java.base/java.util.concurrent.atomic=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED --add-opens java.base/jdk.internal.misc=ALL-UNNAMED"
BACKUP_PREFIX="app.jar.backup."
MAX_BACKUP_COUNT=5
JAVA_PROCESS_KEYWORD="app.jar -port=${PORT}"

normalize_deploy_mode() {
    case "${1:-replace}" in
        replace|coexist)
            echo "${1:-replace}"
            ;;
        *)
            echo "${RED}[${LOCAL_TIME}] 无效发布策略 '${1}'，只支持 replace/coexist${NC}" >&2
            return 1
            ;;
    esac
}

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

candidate_pids() {
    if [ -n "${STARTED_PID:-}" ] && pid_args "${STARTED_PID}" | grep -Fq "app.deploy.id=${DEPLOY_ID}"; then
        echo "${STARTED_PID}"
    fi
    deploy_pids | while IFS= read -r pid; do
        [ -z "${pid}" ] && continue
        if [ -n "${STARTED_PID:-}" ] && [ "${pid}" = "${STARTED_PID}" ]; then
            continue
        fi
        echo "${pid}"
    done
}

pid_alive() {
    local pid="$1"
    [ -n "${pid}" ] && kill -0 "${pid}" >/dev/null 2>&1
}

pid_args() {
    local pid="$1"
    ps -o args= -p "${pid}" 2>/dev/null || true
}

pid_slot() {
    local pid="$1" slot
    slot=$(pid_args "${pid}" | sed -n 's/.*-Dapp\.deploy\.slot=\([^ ]*\).*/\1/p' | head -1)
    if [ -z "${slot}" ]; then
        echo "legacy"
    else
        echo "${slot}"
    fi
}

pid_deploy_id() {
    local pid="$1"
    pid_args "${pid}" | sed -n 's/.*-Dapp\.deploy\.id=\([^ ]*\).*/\1/p' | head -1
}

pid_elapsed_seconds() {
    local pid="$1" elapsed
    elapsed=$(ps -o etimes= -p "${pid}" 2>/dev/null | awk '{print $1}')
    echo "${elapsed:-0}"
}

process_infos() {
    local pid slot elapsed deploy_id
    process_pids | while IFS= read -r pid; do
        [ -z "${pid}" ] && continue
        pid_alive "${pid}" || continue
        slot=$(pid_slot "${pid}")
        elapsed=$(pid_elapsed_seconds "${pid}")
        deploy_id=$(pid_deploy_id "${pid}")
        [ -z "${deploy_id}" ] && deploy_id="-"
        echo "${elapsed} ${pid} ${slot} ${deploy_id}"
    done | sort -rn
}

process_count() {
    local infos
    infos=$(process_infos)
    if [ -z "${infos}" ]; then
        echo 0
    else
        printf '%s\n' "${infos}" | wc -l | awk '{print $1}'
    fi
}

oldest_process_pid() {
    process_infos | awk 'NR==1 {print $2}'
}

print_process_status() {
    local infos
    infos=$(process_infos)
    if [ -z "${infos}" ]; then
        echo "${YELLOW}[${LOCAL_TIME}] 当前无匹配 app.jar 进程${NC}"
        return 0
    fi

    echo "${YELLOW}[${LOCAL_TIME}] 当前进程（oldest first）:${NC}"
    printf '%s\n' "${infos}" | while read -r elapsed pid slot deploy_id; do
        echo "  pid=${pid} slot=${slot} elapsed=${elapsed}s deployId=${deploy_id}"
    done
}

slot_in_use() {
    local expected="$1"
    process_infos | awk -v slot="${expected}" '$3 == slot {found=1} END {exit found ? 0 : 1}'
}

free_slot() {
    local slot
    for slot in "${DEPLOY_SLOTS[@]}"; do
        if ! slot_in_use "${slot}"; then
            echo "${slot}"
            return 0
        fi
    done
    return 1
}

slot_log_file() {
    local slot="$1"
    echo "app-${slot}.out"
}

build_dump_opts() {
    local slot="$1" heap_dir
    heap_dir="${SCRIPT_DIR}/heapdump-${slot}"
    mkdir -p "${heap_dir}" >/dev/null 2>&1 || true
    echo "-Xlog:gc*,gc+age=trace,safepoint:file=./gc-${slot}.log:time,uptime:filecount=10,filesize=10M -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=${heap_dir} -XX:ErrorFile=${SCRIPT_DIR}/hs_err_${slot}_pid%p.log -XX:+CreateCoredumpOnCrash -XX:+ExitOnOutOfMemoryError --add-exports java.base/jdk.internal.ref=ALL-UNNAMED ${JDK21_MODULE_OPTS}"
}

build_logback_opts() {
    local slot="$1"
    echo "-DAPP_LOG_SLOT=${slot}"
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

listener_inodes_for_port() {
    local port="$1" port_hex
    printf -v port_hex '%04X' "${port}"
    awk -v port_hex="${port_hex}" '
        $4 == "0A" {
            split($2, local_addr, ":")
            if (toupper(local_addr[2]) == port_hex) {
                print $10
            }
        }
    ' /proc/net/tcp /proc/net/tcp6 2>/dev/null | sort -u
}

pid_listens_on_procfs() {
    local pid="$1"
    local port="$2"
    local inodes fd link inode

    [ -d "/proc/${pid}/fd" ] || return 1
    inodes=$(listener_inodes_for_port "${port}")
    [ -n "${inodes}" ] || return 1

    for fd in /proc/"${pid}"/fd/*; do
        link=$(readlink "${fd}" 2>/dev/null) || continue
        case "${link}" in
            socket:\[*\])
                inode=${link#socket:[}
                inode=${inode%]}
                printf '%s\n' "${inodes}" | grep -Fxq "${inode}" && return 0
                ;;
        esac
    done
    return 1
}

pids_listening_on_procfs() {
    local port="$1"
    local inodes pid_dir pid fd link inode

    inodes=$(listener_inodes_for_port "${port}")
    [ -n "${inodes}" ] || return 1

    for pid_dir in /proc/[0-9]*; do
        [ -d "${pid_dir}/fd" ] || continue
        pid=${pid_dir#/proc/}
        for fd in "${pid_dir}"/fd/*; do
            [ -e "${fd}" ] || continue
            link=$(readlink "${fd}" 2>/dev/null) || continue
            case "${link}" in
                socket:\[*\])
                    inode=${link#socket:[}
                    inode=${inode%]}
                    if printf '%s\n' "${inodes}" | grep -Fxq "${inode}"; then
                        echo "${pid}"
                        break
                    fi
                    ;;
            esac
        done
    done | sort -n -u
}

pids_listening_on() {
    local port="$1"
    pids_listening_on_procfs "${port}" && return 0
    return 1
}

pid_listens_on() {
    local pid="$1"
    local port="$2"
    pid_listens_on_procfs "${pid}" "${port}" && return 0
    if command -v ss >/dev/null 2>&1; then
        ss -ltnp 2>/dev/null | grep -F "pid=${pid}," | awk '{print $4}' | grep -Eq "(^|:)${port}$" && return 0
    fi
    if command -v netstat >/dev/null 2>&1; then
        netstat -ltnp 2>/dev/null | grep -E "[ /]${pid}/" | awk '{print $4}' | grep -Eq "(^|:)${port}$" && return 0
    fi
    if command -v lsof >/dev/null 2>&1; then
        lsof -Pan -p "${pid}" -iTCP:"${port}" -sTCP:LISTEN 2>/dev/null | awk 'NR > 1 {found=1} END {exit found ? 0 : 1}' && return 0
    fi
    return 1
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

matching_process_owns_required_port() {
    local pid port
    while IFS= read -r pid; do
        [ -z "${pid}" ] && continue
        for port in "${REQUIRED_TCP_PORTS[@]}"; do
            if pid_listens_on "${pid}" "${port}"; then
                return 0
            fi
        done
    done <<EOF
$(process_pids)
EOF
    return 1
}

print_required_port_status() {
    local port state owners
    for port in "${REQUIRED_TCP_PORTS[@]}"; do
        state="free"
        owners=""
        if port_in_use "${port}"; then
            state="in-use"
            owners=$(pids_listening_on "${port}" | tr '\n' ' ' | sed 's/[[:space:]]*$//')
        fi
        if [ -n "${owners}" ]; then
            echo "${YELLOW}[${LOCAL_TIME}] port ${port}/tcp ${state}, listenerPids=${owners}${NC}"
        else
            echo "${YELLOW}[${LOCAL_TIME}] port ${port}/tcp ${state}${NC}"
        fi
    done
}

print_candidate_port_status() {
    local pid port ports candidates any args
    candidates=$(candidate_pids)
    if [ -z "${candidates}" ]; then
        echo "${YELLOW}[${LOCAL_TIME}] 未找到本次启动候选 PID deployId=${DEPLOY_ID}${NC}"
        return 0
    fi

    while IFS= read -r pid; do
        [ -z "${pid}" ] && continue
        ports=""
        for port in "${REQUIRED_TCP_PORTS[@]}"; do
            if pid_listens_on "${pid}" "${port}"; then
                ports="${ports} ${port}=LISTEN"
            else
                ports="${ports} ${port}=NO"
            fi
        done
        any="dead"
        pid_alive "${pid}" && any="alive"
        echo "${YELLOW}[${LOCAL_TIME}] 候选进程 pid=${pid} ${any}${ports}${NC}"
        args=$(pid_args "${pid}")
        [ -n "${args}" ] && echo "  args=${args}"
    done <<EOF
${candidates}
EOF
}

write_drain_token() {
    local pid="$1"
    local new_pid="$2"
    local token_file tmp_file now

    mkdir -p "${DRAIN_TOKEN_DIR}" || return 1
    token_file="${DRAIN_TOKEN_DIR}/rss-drain-${pid}.token"
    tmp_file="${token_file}.${DEPLOY_ID}.tmp"
    now=$(date +%s)
    {
        echo "pid=${pid}"
        echo "newPid=${new_pid}"
        echo "deployId=${DEPLOY_ID}"
        echo "createdAt=${now}"
    } >"${tmp_file}" || return 1
    chmod 600 "${tmp_file}" >/dev/null 2>&1 || true
    mv -f "${tmp_file}" "${token_file}"
}

wait_pid_exit() {
    local pid="$1"
    local timeout="$2"
    local waited
    waited=0
    while [ ${waited} -lt "${timeout}" ]; do
        if ! pid_alive "${pid}"; then
            return 0
        fi
        sleep 1
        waited=$((waited + 1))
    done
    return 1
}

drain_process_and_wait() {
    local pid="$1"
    local new_pid="${2:-}"
    local reason="${3:-旧进程}"

    if [ -z "${pid}" ] || ! pid_alive "${pid}"; then
        return 0
    fi

    echo "${YELLOW}[${LOCAL_TIME}] 通知${reason}进入 drain，PID: ${pid}${NC}"
    if write_drain_token "${pid}" "${new_pid}"; then
        kill -USR1 "${pid}" >/dev/null 2>&1 || kill -15 "${pid}" >/dev/null 2>&1 || true
    else
        echo "${RED}[${LOCAL_TIME}] 写入 drain token 失败，改用 TERM，PID: ${pid}${NC}"
        kill -15 "${pid}" >/dev/null 2>&1 || true
    fi

    if wait_pid_exit "${pid}" "$((DRAIN_TIMEOUT_SECONDS + 5))"; then
        echo "${GREEN}[${LOCAL_TIME}] ${reason}已退出，PID: ${pid}${NC}"
        return 0
    fi

    echo "${YELLOW}[${LOCAL_TIME}] ${reason}未在 drain 超时内退出，执行 KILL，PID: ${pid}${NC}"
    kill -9 "${pid}" >/dev/null 2>&1 || true
    wait_pid_exit "${pid}" 5
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
            if ! write_drain_token "${pid}" "${new_pid}"; then
                echo "${RED}[${LOCAL_TIME}] 写入 drain token 失败，跳过旧进程 PID: ${pid}${NC}"
                continue
            fi
            kill -USR1 "${pid}" >/dev/null 2>&1 || kill -15 "${pid}" >/dev/null 2>&1 || true
            signaled=$((signaled + 1))
        fi
    done <<EOF
${old_pids}
EOF

    if [ ${signaled} -gt 0 ]; then
        echo "${GREEN}[${LOCAL_TIME}] 已通知 ${signaled} 个旧进程 drain；最长保留 ${DRAIN_TIMEOUT_SECONDS}s 后由 JVM 自行退出${NC}"
    fi
}

cleanup_candidate_process() {
    local pid_list pid
    pid_list=$(candidate_pids)
    [ -z "${pid_list}" ] && return 0

    echo "${YELLOW}[${LOCAL_TIME}] 清理启动失败的候选进程 deployId=${DEPLOY_ID}${NC}"
    while IFS= read -r pid; do
        [ -z "${pid}" ] && continue
        kill -15 "${pid}" >/dev/null 2>&1 || true
    done <<EOF
${pid_list}
EOF
    sleep 2
    while IFS= read -r pid; do
        [ -z "${pid}" ] && continue
        pid_alive "${pid}" && kill -9 "${pid}" >/dev/null 2>&1 || true
    done <<EOF
${pid_list}
EOF
}

prepare_capacity_for_new_process() {
    local count oldest_pid oldest_slot
    count=$(process_count)
    while [ "${count}" -ge "${MAX_LIVE_PROCESSES}" ]; do
        oldest_pid=$(oldest_process_pid)
        oldest_slot=$(pid_slot "${oldest_pid}")
        echo "${YELLOW}[${LOCAL_TIME}] 当前已有 ${count} 个进程，先替换最旧进程 PID=${oldest_pid}, slot=${oldest_slot}${NC}"
        drain_process_and_wait "${oldest_pid}" "" "最旧进程" || return 1
        count=$(process_count)
    done
}

start_new_process() {
    local slot="$1"
    local log_file dump_opts logback_opts
    NEW_SLOT="${slot}"
    log_file=$(slot_log_file "${slot}")
    dump_opts=$(build_dump_opts "${slot}")
    logback_opts=$(build_logback_opts "${slot}")
    mkdir -p logs >/dev/null 2>&1 || true
    echo "${YELLOW}[${LOCAL_TIME}] 正在启动新进程 deployId=${DEPLOY_ID}, slot=${slot}, stdout=${log_file}${NC}"
    nohup java ${MEM_OPTIONS} ${GC_OPTIONS} ${APP_OPTIONS} ${dump_opts} ${logback_opts} -Dapp.deploy.id="${DEPLOY_ID}" -Dapp.deploy.slot="${slot}" -Dfile.encoding=UTF-8 -jar app.jar -port=${PORT} >"${log_file}" 2>&1 &
    STARTED_PID=$!
}

wait_for_new_process() {
    local wait_count pid log_file

    wait_count=0
    NEW_PID=""
    log_file=$(slot_log_file "${NEW_SLOT}")
    while [ ${wait_count} -lt ${STARTUP_WAIT_SECONDS} ]; do
        while IFS= read -r pid; do
            [ -z "${pid}" ] && continue
            if pid_alive "${pid}"; then
                if pid_listens_required_ports "${pid}"; then
                    echo "${GREEN}[${LOCAL_TIME}] 新进程已绑定全部必需端口，PID: ${pid}, slot=${NEW_SLOT}${NC}"
                    NEW_PID="${pid}"
                    return 0
                fi
            fi
        done <<EOF
$(candidate_pids)
EOF
        sleep 1
        wait_count=$((wait_count + 1))
    done

    echo "${RED}[${LOCAL_TIME}] 新进程启动失败：未确认 PID 绑定全部必需端口 deployId=${DEPLOY_ID}, slot=${NEW_SLOT}${NC}"
    print_candidate_port_status
    print_required_port_status
    if [ -f "${log_file}" ]; then
        tail -n 100 "${log_file}" || true
    fi
    cleanup_candidate_process
    return 1
}

last_start_looks_first_migration_conflict() {
    local log_file
    log_file=$(slot_log_file "${NEW_SLOT:-a}")
    if [ -f "${log_file}" ] && grep -Eiq "Address already in use|BindException|EADDRINUSE|bind .*fail|bind .*failed|Database may be already in use|database is already in use|Locked by another process|File lock|JdbcSQLNonTransientConnectionException" "${log_file}"; then
        return 0
    fi

    # 首次迁移时旧进程可能不是同一套 SO_REUSEPORT/H2 兼容参数，日志不一定及时刷出异常。
    matching_process_owns_required_port
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

select_start_slot() {
    local slot
    slot=$(free_slot)
    if [ -n "${slot}" ]; then
        echo "${slot}"
        return 0
    fi
    echo "${RED}[${LOCAL_TIME}] 无可用发布槽位，请先检查残留进程${NC}" >&2
    return 1
}

start_release() {
    local mode="$1"
    local label="$2"
    local old_pids new_pid start_slot

    mode=$(normalize_deploy_mode "${mode}") || return 1
    prepare_capacity_for_new_process || return 1
    old_pids=$(process_pids)
    start_slot=$(select_start_slot) || return 1
    start_new_process "${start_slot}"
    wait_for_new_process || return 1
    new_pid="${NEW_PID}"

    if [ "${mode}" = "replace" ]; then
        signal_drain_old_processes "${old_pids}" "${new_pid}"
        echo "${GREEN}[${LOCAL_TIME}] ${label}完成：replace，新进程 PID=${new_pid}, slot=${start_slot}${NC}"
    else
        echo "${GREEN}[${LOCAL_TIME}] ${label}完成：coexist，新进程 PID=${new_pid}, slot=${start_slot}；旧进程保留用于人工验证${NC}"
    fi
    print_process_status
}

publish_release() {
    local mode="$1"
    publish_jar || return 1
    start_release "${mode}" "发布"
}

publish_with_legacy_restart() {
    local slot base_deploy_id
    echo "${YELLOW}[${LOCAL_TIME}] 同端口并行启动失败，执行显式开启的首次迁移兼容重启${NC}"
    base_deploy_id="${DEPLOY_ID}"
    slot=$(select_start_slot) || slot="a"
    while IFS= read -r pid; do
        drain_process_and_wait "${pid}" "" "兼容迁移旧进程" || return 1
    done <<EOF
$(process_pids)
EOF
    DEPLOY_ID="${base_deploy_id}_legacy"
    start_new_process "${slot}"
    if wait_for_new_process; then
        echo "${GREEN}[${LOCAL_TIME}] 兼容迁移重启完成，新进程 PID=${NEW_PID}, slot=${slot}${NC}"
        print_process_status
        return 0
    fi

    echo "${RED}[${LOCAL_TIME}] 兼容迁移重启失败${NC}"
    if [ "${ROLLBACK_ON_LEGACY_FALLBACK_FAILURE}" = "1" ]; then
        echo "${YELLOW}[${LOCAL_TIME}] 尝试恢复 app.jar.latest 并启动旧版本${NC}"
        DEPLOY_ID="${base_deploy_id}_rollback"
        rollback_release || return 1
    fi
    return 1
}

start_if_absent() {
    local start_slot
    if process_pids | grep -q .; then
        print_required_port_status
        print_process_status
        return 0
    fi
    if any_required_port_in_use; then
        print_required_port_status
        echo "${RED}[${LOCAL_TIME}] 必需端口被占用，但未找到匹配进程，请手动检查占用者${NC}"
        return 1
    fi

    start_slot=$(select_start_slot) || return 1
    start_new_process "${start_slot}"
    wait_for_new_process
}

rollback_release() {
    restore_latest_jar || return 1
    start_release "replace" "回滚"
}

usage() {
    echo "用法: $0 [publish|start|rollback|status] [replace|coexist]"
    echo "  publish [replace] : 发布并替换旧进程；新进程端口确认失败时不动旧进程"
    echo "  publish coexist   : 发布并保留旧进程；最多保留 ${MAX_LIVE_PROCESSES} 个进程，满员先替换最旧进程"
    echo "  start             : 不存在匹配进程时启动"
    echo "  rollback          : 回滚到 app.jar.latest，并按 replace 策略切换"
    echo "  status            : 查看当前进程与端口状态"
    exit 1
}

if [ $# -lt 1 ] || [ $# -gt 2 ]; then
    usage
fi

ACTION="$1"
if [ $# -eq 2 ]; then
    DEPLOY_MODE="$2"
fi

case "${ACTION}" in
    publish)
        DEPLOY_MODE=$(normalize_deploy_mode "${DEPLOY_MODE}") || exit 1
        echo "${YELLOW}[${LOCAL_TIME}] 发布模式：mode=${DEPLOY_MODE}, SO_REUSEPORT=${REUSE_PORT_BIND_COUNT}, maxLive=${MAX_LIVE_PROCESSES}, drain=${DRAIN_TIMEOUT_SECONDS}s${NC}"
        if ! publish_release "${DEPLOY_MODE}"; then
            if [ "${DEPLOY_MODE}" = "replace" ] && [ "${REUSEPORT_MIGRATION_FALLBACK}" = "1" ] && last_start_looks_first_migration_conflict; then
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
    status)
        print_required_port_status
        print_process_status
        ;;
    *)
        echo "错误：无效参数 '${ACTION}'"
        usage
        ;;
esac
