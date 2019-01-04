package org.rx.fl.service.media;

import org.rx.bean.DateTime;
import org.rx.fl.model.GoodsInfo;
import org.rx.fl.model.MediaType;
import org.rx.fl.model.OrderInfo;

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
