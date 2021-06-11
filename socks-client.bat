@echo off
taskkill /F /T /im java.exe
timeout /t 1

set JAR=rxlib-2.17.3-SNAPSHOT.jar
set MEM_OPTIONS=-Xms128m -Xmx512m -XX:MaxMetaspaceSize=256m -XX:MaxDirectMemorySize=384m
set JVM_OPTIONS=-XX:+UseConcMarkSweepGC -XX:+CMSScavengeBeforeRemark -XX:+ParallelRefProcEnabled -XX:+CMSConcurrentMTEnabled -XX:+CMSParallelInitialMarkEnabled -XX:+CMSParallelRemarkEnabled -XX:+ExplicitGCInvokesConcurrent -XX:+CMSClassUnloadingEnabled -XX:+UseCMSInitiatingOccupancyOnly -XX:CMSInitiatingOccupancyFraction=70
java %MEM_OPTIONS% %JVM_OPTIONS% -jar %JAR% -port=9900 -connectTimeout=90000 "-shadowServer=youfanX:5PXx0^JNMOgvn3P658@103.79.76.126:9900"
