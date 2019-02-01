package org.rx;

import org.mybatis.spring.annotation.MapperScan;
import org.rx.common.Logger;
import org.rx.fl.util.AwtBot;
import org.rx.socks.Sockets;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportResource;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
@ImportResource("classpath:applicationContext.xml")
@EnableTransactionManagement
@MapperScan("org.rx.fl.repository")
public class Application {
    public static final String PackName = "org.rx";

    public static void main(String[] args) {
//        Sockets.setHttpProxy("127.0.0.1:8888");
//        Logger.info("app start...");
        AwtBot.init();
        SpringApplication.run(Application.class, args);
    }
}
