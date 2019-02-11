package org.rx.fl.service.media;

import org.rx.beans.DateTime;
import org.rx.fl.dto.media.GoodsInfo;
import org.rx.fl.dto.media.MediaType;
import org.rx.fl.dto.media.OrderInfo;
import org.rx.fl.util.WebBrowser;

import java.util.List;

public class KaolaMedia implements Media {
    private WebBrowser caller;

    @Override
    public MediaType getType() {
        return MediaType.Kaola;
    }

    @Override
    public boolean isLogin() {
        return false;
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

    @Override
    public List<OrderInfo> findOrders(DateTime start, DateTime end) {
        return null;
    }
}
