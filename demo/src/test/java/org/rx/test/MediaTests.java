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
        userMessage = "https://item.jd.com/23030257143.html";

        JdMedia media = new JdMedia();

        String url = media.findLink(userMessage);
        assert url != null;
        GoodsInfo goods = media.findGoods(url);
        assert goods != null;

        media.login();
        String code = media.findAdv(goods);
        System.out.println(code);

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

        System.in.read();
    }

    @SneakyThrows
    @Test
    public void tbMedia() {
        String userMessage =
                "https://item.taobao.com/item.htm?spm=a230r.1.14.316.5aec2f1f1KyoMC&id=580168318999&ns=1&abbucket=1#detail";

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
