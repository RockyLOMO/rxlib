package org.rx.lr.repository.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.rx.lr.repository.model.common.DataObject;

/**
 * 用户
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class User extends DataObject {
    private String userName;
    private String password;
    private String email;
    private String mobile;
    private int level;
    private int status;
}
