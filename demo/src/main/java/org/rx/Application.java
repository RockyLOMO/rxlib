package org.rx;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportResource;
import org.springframework.transaction.annotation.EnableTransactionManagement;

//@SpringBootApplication(scanBasePackages = PackName)
@SpringBootApplication
@ImportResource("classpath:applicationContext.xml")
@EnableTransactionManagement
@MapperScan("org.rx.fl.repository")
public class Application {
    public static final String PackName = "org.rx";

    public static void main(String[] args) {
        Logger.debug("app start.."); //init path
//        Sockets.setHttpProxy("127.0.0.1:8888");

        SpringApplication.run(Application.class, args);
    }
}
