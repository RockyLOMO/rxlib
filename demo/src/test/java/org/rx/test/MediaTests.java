package org.rx.test;

import com.google.common.base.Strings;
import lombok.SneakyThrows;
import org.junit.Test;
import org.rx.beans.DateTime;
import org.rx.common.App;
import org.rx.fl.dto.media.GoodsInfo;
import org.rx.fl.dto.media.OrderInfo;
import org.rx.fl.service.media.JdMedia;
import org.rx.fl.service.media.Media;
import org.rx.fl.service.media.TbMedia;

import java.util.List;
import java.util.function.Function;

import static org.rx.common.Contract.toJsonString;

public class MediaTests {
    @SneakyThrows
    @Test
    public void jdMedia() {
        String userMessage = "https://u.jd.com/bdJddY";
        userMessage = "https://u.jd.com/lRB5js";
        userMessage = "https://item.jd.com/3167821.html?jd_pop=8284c4cb-9a27-4849-a2f0-1cae25583d40";

        JdMedia media = new JdMedia();

//        String url = media.findLink(userMessage);
//        assert url != null;
//        GoodsInfo goods = media.findGoods(url);
//        assert goods != null;

        media.login();
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
//
//        List<OrderInfo> orders = media.findOrders(DateTime.now().addDays(-7), DateTime.now());
//        System.out.println(toJsonString(orders));

        Thread.sleep(2000);
    }

    @SneakyThrows
    @Test
    public void tbMedia() {
        String userMessage =
                "复制整段信息，打开手机天猫，即可查看此商品:【防水加厚保暖浴帘浴室套装免打孔卫生间洗澡淋浴隔断挂帘子门帘布】(未安装App点这里：http://yukhj.com/s/8mUtL?tm=caf77e )喵口令";

        TbMedia media = new TbMedia();
        media.setShareCookie(false);

        String url = media.findLink(userMessage);
        assert url != null;
        GoodsInfo goods = media.findGoods(url);
        assert goods != null;

        media.login();
        String code = media.findAdv(goods);

        Function<String, Double> convert = p -> {
            if (Strings.isNullOrEmpty(p)) {
                return 0d;
            }
            return App.changeType(p.replace("￥", "")
                    .replace("¥", ""), double.class);
        };
        Double payAmount = convert.apply(goods.getPrice())
                - convert.apply(goods.getRebateAmount())
                - convert.apply(goods.getCouponAmount());
        String content = String.format("约反%s 优惠券%s 付费价￥%.2f；复制框内整段文字，打开「手淘」即可「领取优惠券」并购买%s",
                goods.getRebateAmount(), goods.getCouponAmount(), payAmount, code);
        System.out.println(content);

        List<OrderInfo> orders = media.findOrders(DateTime.now().addDays(-7), DateTime.now());
        System.out.println(toJsonString(orders));

//        Thread.sleep(2000);
        System.in.read();
    }
}
