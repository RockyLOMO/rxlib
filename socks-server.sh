PORT=9900
JAR="rxlib-2.17.3-SNAPSHOT.jar"
MEM_OPTIONS="-Xms128m -Xmx512m -XX:MaxMetaspaceSize=256m -XX:MaxDirectMemorySize=384m"

netstat -nlp|grep :${PORT}|grep -v grep|awk '{print $7}'|awk -F '/' '{print $1}'|xargs kill -9
java ${MEM_OPTIONS} -jar ${JAR} -shadowMode=1 -port=${PORT} -connectTimeout=90000 "-shadowUser=youfanX:5PXx0^JNMOgvn3P658@f-li.cn:9900" &
