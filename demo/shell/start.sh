#!/usr/bin/env bash
## yum install lsof
#PID = `lsof -i:80 |grep -v "PID" | awk '{print $2}'`
#if [ "" != "${PID}" ]; then
#	kill -9 ${PID}
#	echo "Kill PID is ${PID}"
#	sleep 2
#fi

netstat -nlp |grep :80|grep -v grep|awk '{print $7}'|awk -F '/' '{print $1}'|xargs kill -9

java -Dserver.port=80 -jar rxlib.lr-1.0.0.jar
