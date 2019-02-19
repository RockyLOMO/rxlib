package org.rx.fl.service.user;

import lombok.Data;

import java.io.Serializable;

@Data
public final class UserNode implements Serializable {
    private String id;
    private Integer percent;
    private boolean isExist;
}
