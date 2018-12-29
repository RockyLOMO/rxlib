package org.rx.fl.service;

import org.rx.fl.model.GoodsInfo;
import org.rx.fl.model.MediaType;

public interface Media {
    MediaType getType();

    boolean isLogin();

    void keepLogin();

    void login();

    String findLink(String content);

    GoodsInfo findGoods(String url);

    String findCouponAmount(String url);

    String findAdv(GoodsInfo goodsInfo);
}
