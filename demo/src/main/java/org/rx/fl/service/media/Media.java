package org.rx.fl.service.media;

import org.rx.beans.DateTime;
import org.rx.fl.dto.media.GoodsInfo;
import org.rx.fl.dto.media.MediaType;
import org.rx.fl.dto.media.OrderInfo;

import java.util.List;

public interface Media {
    MediaType getType();

    boolean isLogin();

    void login();

    List<OrderInfo> findOrders(DateTime start, DateTime end);

    String findLink(String content);

    GoodsInfo findGoods(String url);

    String findCouponAmount(String url);

    String findAdv(GoodsInfo goodsInfo);
}
