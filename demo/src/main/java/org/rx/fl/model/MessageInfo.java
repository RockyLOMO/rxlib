package org.rx.fl.model;

import lombok.Data;

@Data
public class MessageInfo {
    private boolean subscribe;
    private String openId;
    private String content;
}
