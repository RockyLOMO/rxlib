package org.rx.fl.repository.model;

import java.io.Serializable;
import java.util.Date;

/**
 * t_cache_item
 * @author 
 */
public class CacheItem implements Serializable {
    private String key;

    private String value;

    private Date createTime;

    private Date expireTime;

    private static final long serialVersionUID = 1L;

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public Date getExpireTime() {
        return expireTime;
    }

    public void setExpireTime(Date expireTime) {
        this.expireTime = expireTime;
    }
}