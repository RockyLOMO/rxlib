package org.rx;

import com.google.common.base.Function;
import com.google.common.base.Strings;
import lombok.SneakyThrows;
import org.rx.fl.model.GoodsInfo;
import org.rx.fl.service.Media;
import org.rx.fl.service.TbMedia;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportResource;

import java.util.List;

import static org.rx.Application.PackName;

@SpringBootApplication(scanBasePackages = PackName)
@ImportResource("classpath:applicationContext.xml")
public class Application {
    public static final String PackName = "org.rx";

    @SneakyThrows
    public static void main(String[] args) {
        Logger.debug("app start.."); //init path

//                Sockets.setHttpProxy("127.0.0.1:8888");
//        testMedia();
        getCode(new String[]{
                "【百吉猫锅巴150g*10大米豆香椒香麻辣烧烤味陕西西安特产膨化零食】https://m.tb.cn/h.3qJoZ5s 点击链接，再选择浏览器咑閞；或復·制这段描述￥rQjsbL6VJJK￥后到淘♂寳♀[来自超级会员的分享]",
                "【小王子董小姐薯片大礼包烘焙组合纯薯薯条混合装礼箱礼盒休闲零食】https://m.tb.cn/h.3qJpEMo 点击链接，再选择浏览器咑閞；或復·制这段描述￥rQq4bL64cVF￥后到淘♂寳♀[来自超级会员的分享]",
                "【阿香婆红油香辣牛肉酱麻味200g辣椒酱 陕西特产干拌面酱类调料】https://m.tb.cn/h.3qxSSs4 点击链接，再选择浏览器咑閞；或復·制这段描述￥hBK7bL644uY￥后到淘♂寳♀[来自超级会员的分享]"}
        );

//        SpringApplication.run(Application.class, args);
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

    @SneakyThrows
    private static void getCode(String[] source) {
        Media media = new TbMedia();
        for (String s : source) {
            String url = media.findLink(s);

            GoodsInfo goods = media.findGoods(url);

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
        }
        System.in.read();
    }
}
