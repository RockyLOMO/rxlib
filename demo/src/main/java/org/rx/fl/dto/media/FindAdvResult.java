package org.rx.fl.dto.media;

import lombok.Data;

@Data
public class FindAdvResult {
    private MediaType mediaType;
    private AdvFoundStatus foundStatus;
    private String link;
    private GoodsInfo goods;
    private String shareCode;
}
