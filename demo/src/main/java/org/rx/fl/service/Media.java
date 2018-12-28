package org.rx.fl.service;

import org.rx.fl.model.GoodsInfo;

public interface Media {
    boolean isLogin();

    void login();

    String findLink(String content);

    GoodsInfo findGoods(String url);

    String findCouponAmount(String url);

    String findAdv(GoodsInfo goodsInfo);
}
