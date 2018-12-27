package org.rx.fl.model;

import lombok.Data;

import java.io.Serializable;

@Data
public class GoodsInfo implements Serializable {
    private String title;
    private String sellerId;
    private String sellerNickname;
}
