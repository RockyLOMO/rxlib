package org.rx.fl.model;

import lombok.Data;

import java.io.Serializable;

@Data
public class BotUser implements Serializable {
    private String openId;
    private String nickname;
    private String unread;
}
