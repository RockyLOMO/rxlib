package org.rx.fl.dto.bot;

import lombok.Data;
import lombok.EqualsAndHashCode;

import javax.validation.constraints.NotNull;

@Data
@EqualsAndHashCode(callSuper = true)
public class MessageInfo extends OpenIdInfo {
    @NotNull
    private String content;
}
