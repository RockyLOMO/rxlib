package org.rx.fl.service;

import org.rx.fl.model.GoodsInfo;

public interface Media {
    boolean isLogin();

    void login();

    GoodsInfo findGoods(String url);

    String findAdv(GoodsInfo goodsInfo);
}
