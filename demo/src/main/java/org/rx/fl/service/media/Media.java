package org.rx.fl.service.media;

import lombok.SneakyThrows;
import org.rx.beans.DateTime;
import org.rx.fl.dto.media.FindAdvResult;
import org.rx.fl.dto.media.GoodsInfo;
import org.rx.fl.dto.media.MediaType;
import org.rx.fl.dto.media.OrderInfo;

import java.util.List;

public interface Media {
    MediaType getType();

    @SneakyThrows
    default void delay(int millis) {
        Thread.sleep(millis);
    }

    boolean isLogin();

    void login();

    String findLink(String content);

    GoodsInfo findGoods(String url);

    String findCouponAmount(String url);

    String findAdv(GoodsInfo goodsInfo);

    List<OrderInfo> findOrders(DateTime start, DateTime end);

    FindAdvResult getHighCommissionAdv(String goodsName);
}
