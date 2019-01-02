package org.rx.fl.model;

import lombok.Data;

import java.io.Serializable;

@Data
public class GoodsInfo implements Serializable {
    private String title;
    private String imageUrl;
    private String sellerId;
    private String sellerNickname;
    private String price;
    private String backRate;
    private String backMoney;
    private String couponAmount;
}
