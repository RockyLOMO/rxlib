package org.rx;

import lombok.SneakyThrows;
import org.rx.fl.model.GoodsInfo;
import org.rx.fl.service.Media;
import org.rx.fl.service.TbMedia;
import org.rx.socks.Sockets;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportResource;

import static org.rx.Application.PackName;

@SpringBootApplication(scanBasePackages = PackName)
@ImportResource("classpath:applicationContext.xml")
//@EnableAutoConfiguration
public class Application {
    public static final String PackName = "org.rx";

    @SneakyThrows
    public static void main(String[] args) {
        Logger.debug("app start.."); //init path
//                Sockets.setHttpProxy("127.0.0.1:8888");
        Media media = new TbMedia();
        GoodsInfo goods = media.findGoods("https://m.tb.cn/h.3qxSSs4");
        String code = media.findAdv(goods);
        System.out.println("code: " + code);
        System.in.read();
//        SpringApplication.run(Application.class, args);
    }
}
