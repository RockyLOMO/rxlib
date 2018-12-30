package org.rx;

import com.google.common.base.Function;
import com.google.common.base.Strings;
import lombok.SneakyThrows;
import org.rx.fl.model.GoodsInfo;
import org.rx.fl.service.Media;
import org.rx.fl.service.TbMedia;
import org.rx.fl.util.WebCaller;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportResource;

import static org.rx.Application.PackName;

//@SpringBootApplication(scanBasePackages = PackName)
@SpringBootApplication
@ImportResource("classpath:applicationContext.xml")
public class Application {
    public static final String PackName = "org.rx";

    @SneakyThrows
    public static void main(String[] args) {
        Logger.debug("app start.."); //init path

//        Sockets.setHttpProxy("127.0.0.1:8888");
//        WebCaller.init(2);
//        System.in.read();
//        testMedia();

        SpringApplication.run(Application.class, args);
    }

    @SneakyThrows
    private static void testMedia() {
        Media media = new TbMedia();
        String url;
        GoodsInfo goods;

        url = media.findLink("【Apple/苹果 iPhone 8 Plus苹果8代5.5寸分期8p正品手机 苹果8plus】https://m.tb.cn/h.3JWcCjA 点击链接，再选择浏览器咑閞；或復·制这段描述￥x4aVbLnW5Cz￥后到淘♂寳♀");
        goods = media.findGoods(url);

        media.login();
        String code = media.findAdv(goods);

        Function<String, Double> convert = p -> {
            if (Strings.isNullOrEmpty(p)) {
                return 0d;
            }
            return App.changeType(p.replace("￥", ""), double.class);
        };
        Double payAmount = convert.apply(goods.getPrice())
                - convert.apply(goods.getBackMoney())
                - convert.apply(goods.getCouponAmount());
        String content = String.format("约反%s 优惠券%s 付费价￥%.2f；复制框内整段文字，打开「手淘」即可「领取优惠券」并购买%s",
                goods.getBackMoney(), goods.getCouponAmount(), payAmount, code);
        System.out.println(content);

        System.in.read();
    }
}
