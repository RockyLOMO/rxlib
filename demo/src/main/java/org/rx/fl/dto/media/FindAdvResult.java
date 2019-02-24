package org.rx.fl.dto.media;

import lombok.Data;

import java.io.Serializable;

@Data
public class FindAdvResult implements Serializable {
    private MediaType mediaType;
    private AdvFoundStatus foundStatus;
    private String link;
    private GoodsInfo goods;
    private String shareCode;
}
