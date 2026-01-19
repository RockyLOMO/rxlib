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
DNS_PORT=753
MEM_OPTIONS="-Xms512m -Xmx1g -Xss512k -XX:MaxMetaspaceSize=128m -XX:MaxDirectMemorySize=2g -XX:-OmitStackTraceInFastThrow -XX:+UseCompressedClassPointers -XX:+UseStringDeduplication"
APP_OPTIONS="-Dapp.net.reactorThreadAmount=10 -Dapp.net.connectTimeoutMillis=10000 -Dapp.net.dns.inlandServers=192.168.31.1:53"
DUMP_OPTS="-Xlog:gc*,gc+age=trace,safepoint:file=./gc.log:time,uptime:filecount=10,filesize=10M -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=$SCRIPT_DIR/ -XX:ErrorFile=$SCRIPT_DIR/hs_err_pid%p.log -XX:+CreateCoredumpOnCrash -XX:+ExitOnOutOfMemoryError --add-exports java.base/jdk.internal.ref=ALL-UNNAMED"

echo "${RED}[${LOCAL_TIME}] 发布模式：正在终止端口 ${PORT}/tcp 的旧进程..."
sudo fuser -k ${PORT}/tcp >/dev/null 2>&1 || true
sleep 2  # 等待进程完全退出和端口释放

if [ -f "app.jar.latest" ]; then
  rm -f "app.jar"
  mv "app.jar.latest" "app.jar"
fi

echo "${YELLOW}[${LOCAL_TIME}] 正在启动 ${PORT}/tcp 的进程..."
nohup java ${MEM_OPTIONS} ${APP_OPTIONS} ${DUMP_OPTS} -Dfile.encoding=UTF-8 -jar app.jar -port=${PORT} >/dev/null 2>&1 &
sleep 5

if fuser ${PORT}/tcp >/dev/null 2>&1; then
    PID=$(fuser ${PORT}/tcp 2>/dev/null | awk '{print $1}' | head -1)
    echo "${GREEN}[${LOCAL_TIME}] 启动成功！PID: ${PID}"
else
    echo "${RED}[${LOCAL_TIME}] 启动失败！请手动执行查看错误"
    exit 1
fi
