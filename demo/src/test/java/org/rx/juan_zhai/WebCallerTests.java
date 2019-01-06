package org.rx.juan_zhai;

import com.alibaba.fastjson.JSON;
import lombok.SneakyThrows;
import org.junit.Test;
import org.rx.bean.DateTime;
import org.rx.fl.model.OrderInfo;
import org.rx.fl.service.media.Media;
import org.rx.fl.service.media.TbMedia;
import org.rx.fl.util.WebCaller;

import java.util.List;

public class WebCallerTests {
    @SneakyThrows
    @Test
    public void testMedia() {
        Media media = new TbMedia();
//        String url;
//        GoodsInfo goods;
//
//        url = media.findLink("【Apple/苹果 iPhone 8 Plus苹果8代5.5寸分期8p正品手机 苹果8plus】https://m.tb.cn/h.3JWcCjA 点击链接，再选择浏览器咑閞；或復·制这段描述￥x4aVbLnW5Cz￥后到淘♂寳♀");
//        goods = media.findGoods(url);
//
//        media.login();
//        String code = media.findAdv(goods);
//
//        Function<String, Double> convert = p -> {
//            if (Strings.isNullOrEmpty(p)) {
//                return 0d;
//            }
//            return App.changeType(p.replace("￥", ""), double.class);
//        };
//        Double payAmount = convert.apply(goods.getPrice())
//                - convert.apply(goods.getRebateAmount())
//                - convert.apply(goods.getCouponAmount());
//        String content = String.format("约反%s 优惠券%s 付费价￥%.2f；复制框内整段文字，打开「手淘」即可「领取优惠券」并购买%s",
//                goods.getRebateAmount(), goods.getCouponAmount(), payAmount, code);
//        System.out.println(content);

        media.login();
        List<OrderInfo> orders = media.findOrders(DateTime.now().addDays(-30), DateTime.now());
        System.out.println(JSON.toJSONString(orders));

        System.in.read();
    }

    @SneakyThrows
    @Test
    public void testTab() {
        WebCaller caller = new WebCaller();
        String currentHandle = caller.getCurrentHandle();
        System.out.println(currentHandle);

        String handle = caller.openTab();
        System.out.println(handle);
        Thread.sleep(2000);

        caller.openTab();
        System.out.println(handle);
        Thread.sleep(2000);

        caller.switchTab(handle);
        System.out.println("switch");
        Thread.sleep(2000);

        caller.closeTab(handle);
        System.out.println("close");
    }
}
