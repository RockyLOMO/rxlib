package org.rx.fl.service;

import lombok.extern.slf4j.Slf4j;
import org.rx.fl.model.GoodsInfo;
import org.rx.fl.model.MediaType;

@Slf4j
public class JdMedia implements Media {
    @Override
    public MediaType getType() {
        return MediaType.Jd;
    }

    @Override
    public boolean isLogin() {
        return false;
    }

    @Override
    public void keepLogin() {

    }

    @Override
    public void login() {

    }

    @Override
    public String findLink(String content) {
        return null;
    }

    @Override
    public GoodsInfo findGoods(String url) {
        return null;
    }

    @Override
    public String findCouponAmount(String url) {
        return null;
    }

    @Override
    public String findAdv(GoodsInfo goodsInfo) {
        return null;
    }
}
