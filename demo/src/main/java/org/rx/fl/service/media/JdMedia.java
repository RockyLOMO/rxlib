package org.rx.fl.service.media;

import lombok.extern.slf4j.Slf4j;
import org.rx.beans.DateTime;
import org.rx.fl.dto.media.GoodsInfo;
import org.rx.fl.dto.media.MediaType;
import org.rx.fl.dto.media.OrderInfo;

import java.util.List;

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

//    @Override
//    public void keepLogin() {
//
//    }

    @Override
    public void login() {

    }

    @Override
    public List<OrderInfo> findOrders(DateTime start, DateTime end) {
        return null;
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
