#!/bin/sh
# 守护脚本，通过 grep 命令检查进程是否存在然后重新启动进程
# 可以配置多个进程
        # 根据进程名判断进程是否消失（行数为0），红字为查询进程关键字，必须唯一
        if test `ps -ef | grep 'frps' |grep -v vi |grep -v grep| wc -l` -eq 0
        then
                #记录发生时间 ，记录丢失进程名
                echo `date`
                echo 'frps is missing.And the programme is restarting...!'
                #重新启动程序，自己配置
                cd /usr/local/_rx/frp_0.22.0_linux_386/
                ./frps -c ./frps.ini
        fi

        # 多个进程守护（配置方法与上方相同）
        # if test `ps -ef | grep INASProcessManager |grep -v vi |grep -v grep| wc -l` -eq 0
        # then
        #         echo 'INASProcessManager is missing!'
        #         echo 'INASProcessManager starting!'
        #         java -jar /jh_app/inas/bin/INASProcessManager.jar >> mng.dump &
        # fi
        # */3 * * * * /bin/sh /usr/local/_rx/daemon.sh>>/usr/local/_rx/daemon.log