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
APP_OPTIONS="-Dapp.disk.h2Settings=CACHE_SIZE=4096;COMPRESS=false;PAGE_SIZE=2048;MAX_COMPACT_TIME=100 -Dapp.net.reactorThreadAmount=2 -Dapp.net.connectTimeoutMillis=8000 -Dio.netty.allocator.type=pooled -Dio.netty.allocator.maxOrder=9 -Dapp.net.dns.outlandServers=1.1.1.1:53"
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

# 回滚前先把当前运行包改名归档，避免直接删除。
archive_current_jar() {
  local backup_file
  if [ ! -f "app.jar" ]; then
    return 0
  fi

  backup_file=$(next_backup_file)
  mv "app.jar" "${backup_file}"
  echo "${YELLOW}[${LOCAL_TIME}] 已归档当前 jar -> ${backup_file}"
  cleanup_backup_jars
}

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

run_fuser_kill() {
  local signal_arg="${1:-}"
  local fuser_cmd
  fuser_cmd=$(get_fuser_cmd) || return 0
  "${fuser_cmd}" ${signal_arg} ${PORT}/tcp >/dev/null 2>&1 && return 0
  sudo -n "${fuser_cmd}" ${signal_arg} ${PORT}/tcp >/dev/null 2>&1 && return 0
  return 0
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

echo "${RED}[${LOCAL_TIME}] 发布模式：正在终止端口 ${PORT}/tcp 的旧进程..."
run_fuser_kill "-k"
kill_by_pattern "-15"
sleep 2  # 等待进程完全退出和端口释放

if [ -f "app.jar.latest" ]; then
  archive_current_jar
  mv "app.jar.latest" "app.jar"
fi

echo "${YELLOW}[${LOCAL_TIME}] 正在启动 ${PORT}/tcp 的进程..."
nohup java ${MEM_OPTIONS} ${APP_OPTIONS} ${DUMP_OPTS} -Dfile.encoding=UTF-8 -jar app.jar -port=${PORT} -shadowMode=1 "-shadowUser=youfanX:5PXx0^JNMOgvn3P658@f-li.cn:9900" >/dev/null 2>&1 &
sleep 5

if /usr/sbin/fuser ${PORT}/tcp >/dev/null 2>&1; then
    PID=$(/usr/sbin/fuser ${PORT}/tcp 2>/dev/null | awk '{print $1}' | head -1)
    echo "${GREEN}[${LOCAL_TIME}] 启动成功！PID: ${PID}"
else
    echo "${RED}[${LOCAL_TIME}] 启动失败！请手动执行查看错误"
    exit 1
fi
