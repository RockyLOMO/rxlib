package org.rx.fl.dto;

import lombok.Data;

@Data
public class MessageInfo {
    private boolean subscribe;
    private String openId;
    private String content;
}
