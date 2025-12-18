ROOT_PATH="$1"
JAR_NAME="$2"
JVM_OPTIONS="$3"
PORT="$4"
JMX_PORT="$5"

cd "$ROOT_PATH"
if [ -f "$JAR_NAME.latest" ]; then
  rm -f "$JAR_NAME"
  mv "$JAR_NAME.latest" "$JAR_NAME"
fi

netstat -nlp|grep :${PORT}|grep -v grep|awk '{print $7}'|awk -F '/' '{print $1}'|xargs kill -9
java -javaagent:./jmx_prometheus_javaagent.jar=$JMX_PORT:./jmx_prometheus_config.yaml $JVM_OPTIONS -Dspring.profiles.active=prd -Dfile.encoding=UTF-8 -jar $JAR_NAME &
