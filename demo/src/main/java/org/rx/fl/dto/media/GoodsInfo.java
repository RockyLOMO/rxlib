package org.rx.fl.dto.media;

import lombok.Data;

import java.io.Serializable;

@Data
public class GoodsInfo implements Serializable {
    private String id;
    private String name;
    private String imageUrl;
    private String sellerId;
    private String sellerName;
    private String price;
    private String rebateRatio;
    private String rebateAmount;
    private String couponAmount;
}
