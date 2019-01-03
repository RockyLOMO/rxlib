package org.rx.fl.repository.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.rx.repository.DataObject;

@Data
@EqualsAndHashCode(callSuper = true)
public class FlUser extends DataObject {
    private String openId;
    private String nickname;
    private String unread;
}
