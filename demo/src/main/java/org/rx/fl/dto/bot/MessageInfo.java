package org.rx.fl.dto.bot;

import lombok.Data;
import lombok.EqualsAndHashCode;

import javax.validation.constraints.NotNull;

import static org.rx.common.Contract.require;

@Data
@EqualsAndHashCode(callSuper = true)
public class MessageInfo extends OpenIdInfo {
    @NotNull
    private String content;

    public MessageInfo() {
    }

    public MessageInfo(OpenIdInfo openId) {
        require(openId);

        this.setBotType(openId.getBotType());
        this.setOpenId(openId.getOpenId());
        this.setNickname(openId.getNickname());
    }
}
