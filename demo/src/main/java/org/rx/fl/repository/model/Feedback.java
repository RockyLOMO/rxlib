package org.rx.fl.repository.model;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
public class Feedback implements Serializable {
    private String id;

    private String userId;

    private String content;

    private String reply;

    private Integer status;

    private Date createTime;

    private Date modifyTime;

    private String isDeleted;
}
