package org.rx.lr;

import org.rx.Logger;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportResource;

import static org.rx.lr.Application.PackName;

@SpringBootApplication(scanBasePackages = PackName)
@ImportResource("classpath:springContext.xml")
@EnableAutoConfiguration
public class Application {
    public static final String PackName = "org.rx.lr";

    public static void main(String[] args) {
        Logger.debug("app start.."); //init path
        SpringApplication.run(Application.class, args);
    }
}
