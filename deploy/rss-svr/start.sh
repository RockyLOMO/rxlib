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
APP_OPTIONS="-Dapp.disk.h2Settings=CACHE_SIZE=4096;COMPRESS=false;PAGE_SIZE=2048;MAX_COMPACT_TIME=100 -Dapp.net.reactorThreadAmount=2 -Dapp.net.connectTimeoutMillis=15000 -Dio.netty.allocator.type=pooled -Dio.netty.allocator.maxOrder=9 -Dapp.net.dns.outlandServers=1.1.1.1:53 -Djava.net.preferIPv4Stack=true"
DUMP_OPTS="-Xlog:gc*,gc+age=trace,safepoint:file=./gc.log:time,uptime:filecount=10,filesize=10M -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=./heapdump-$(date +%Y%m%d_%H%M%S).hprof -XX:ErrorFile=./hs_err_pid%p.log -XX:+CreateCoredumpOnCrash"

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
    sudo /usr/sbin/fuser -k ${PORT}/tcp >/dev/null 2>&1 || true
    sleep 2  # 等待进程完全退出和端口释放

    if [ -f "app.jar.publish" ]; then
        mv "app.jar" "app.jar.latest"
        mv "app.jar.publish" "app.jar"
    fi
elif [ "$ACTION" = "start" ]; then
    echo "${RED}[${LOCAL_TIME}] 启动模式：正在检测端口 ${PORT}/tcp 的进程..."
    if /usr/sbin/fuser ${PORT}/tcp >/dev/null 2>&1; then
        PID=$(/usr/sbin/fuser ${PORT}/tcp 2>/dev/null | awk '{print $1}' | head -1)
        echo "${GREEN}[${LOCAL_TIME}] ${PORT}/tcp 已运行，PID: ${PID}"
        exit 0
    fi
else
    echo "错误：无效参数 '$ACTION'"
    usage
fi

echo "${YELLOW}[${LOCAL_TIME}] 正在启动 ${PORT}/tcp 的进程..."
nohup java ${MEM_OPTIONS} ${APP_OPTIONS} ${DUMP_OPTS} -Dfile.encoding=UTF-8 -jar app.jar -port=${PORT} -shadowMode=1 -udp2raw=1 "-shadowUser=youfanX:5PXx0^JNMOgvn3P658@f-li.cn:9900" >/dev/null 2>&1 &
sleep 5

if /usr/sbin/fuser ${PORT}/tcp >/dev/null 2>&1; then
    PID=$(/usr/sbin/fuser ${PORT}/tcp 2>/dev/null | awk '{print $1}' | head -1)
    echo "${GREEN}[${LOCAL_TIME}] 启动成功！PID: ${PID}"
else
    echo "${RED}[${LOCAL_TIME}] 启动失败！请手动执行查看错误"
    exit 1
fi
