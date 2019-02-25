package org.rx.fl.dto.media;

import lombok.Data;

import javax.validation.constraints.NotNull;
import java.io.Serializable;

@Data
public class GoodsInfo implements Serializable {
    @NotNull
    private String id;
    @NotNull
    private String name;
    private String imageUrl;
    private String sellerId;
    private String sellerName;
    private String price;
    private String rebateRatio;
    private String rebateAmount;
    private String couponAmount;
}
