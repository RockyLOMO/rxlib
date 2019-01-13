package org.rx.fl.repository.model;

import java.io.Serializable;
import java.util.Date;

/**
 * t_user
 * @author 
 */
public class User implements Serializable {
    private String id;

    private String nickname;

    private String wxSvcOpenId;

    private String wxOpenId;

    private String alipayName;

    private String alipayAccount;

    private Long balance;

    private Long freezeAmount;

    private Long version;

    private Date createTime;

    private Date modifyTime;

    private String isDeleted;

    private static final long serialVersionUID = 1L;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getWxSvcOpenId() {
        return wxSvcOpenId;
    }

    public void setWxSvcOpenId(String wxSvcOpenId) {
        this.wxSvcOpenId = wxSvcOpenId;
    }

    public String getWxOpenId() {
        return wxOpenId;
    }

    public void setWxOpenId(String wxOpenId) {
        this.wxOpenId = wxOpenId;
    }

    public String getAlipayName() {
        return alipayName;
    }

    public void setAlipayName(String alipayName) {
        this.alipayName = alipayName;
    }

    public String getAlipayAccount() {
        return alipayAccount;
    }

    public void setAlipayAccount(String alipayAccount) {
        this.alipayAccount = alipayAccount;
    }

    public Long getBalance() {
        return balance;
    }

    public void setBalance(Long balance) {
        this.balance = balance;
    }

    public Long getFreezeAmount() {
        return freezeAmount;
    }

    public void setFreezeAmount(Long freezeAmount) {
        this.freezeAmount = freezeAmount;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public Date getModifyTime() {
        return modifyTime;
    }

    public void setModifyTime(Date modifyTime) {
        this.modifyTime = modifyTime;
    }

    public String getIsDeleted() {
        return isDeleted;
    }

    public void setIsDeleted(String isDeleted) {
        this.isDeleted = isDeleted;
    }

    @Override
    public boolean equals(Object that) {
        if (this == that) {
            return true;
        }
        if (that == null) {
            return false;
        }
        if (getClass() != that.getClass()) {
            return false;
        }
        User other = (User) that;
        return (this.getId() == null ? other.getId() == null : this.getId().equals(other.getId()))
            && (this.getNickname() == null ? other.getNickname() == null : this.getNickname().equals(other.getNickname()))
            && (this.getWxSvcOpenId() == null ? other.getWxSvcOpenId() == null : this.getWxSvcOpenId().equals(other.getWxSvcOpenId()))
            && (this.getWxOpenId() == null ? other.getWxOpenId() == null : this.getWxOpenId().equals(other.getWxOpenId()))
            && (this.getAlipayName() == null ? other.getAlipayName() == null : this.getAlipayName().equals(other.getAlipayName()))
            && (this.getAlipayAccount() == null ? other.getAlipayAccount() == null : this.getAlipayAccount().equals(other.getAlipayAccount()))
            && (this.getBalance() == null ? other.getBalance() == null : this.getBalance().equals(other.getBalance()))
            && (this.getFreezeAmount() == null ? other.getFreezeAmount() == null : this.getFreezeAmount().equals(other.getFreezeAmount()))
            && (this.getVersion() == null ? other.getVersion() == null : this.getVersion().equals(other.getVersion()))
            && (this.getCreateTime() == null ? other.getCreateTime() == null : this.getCreateTime().equals(other.getCreateTime()))
            && (this.getModifyTime() == null ? other.getModifyTime() == null : this.getModifyTime().equals(other.getModifyTime()))
            && (this.getIsDeleted() == null ? other.getIsDeleted() == null : this.getIsDeleted().equals(other.getIsDeleted()));
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((getId() == null) ? 0 : getId().hashCode());
        result = prime * result + ((getNickname() == null) ? 0 : getNickname().hashCode());
        result = prime * result + ((getWxSvcOpenId() == null) ? 0 : getWxSvcOpenId().hashCode());
        result = prime * result + ((getWxOpenId() == null) ? 0 : getWxOpenId().hashCode());
        result = prime * result + ((getAlipayName() == null) ? 0 : getAlipayName().hashCode());
        result = prime * result + ((getAlipayAccount() == null) ? 0 : getAlipayAccount().hashCode());
        result = prime * result + ((getBalance() == null) ? 0 : getBalance().hashCode());
        result = prime * result + ((getFreezeAmount() == null) ? 0 : getFreezeAmount().hashCode());
        result = prime * result + ((getVersion() == null) ? 0 : getVersion().hashCode());
        result = prime * result + ((getCreateTime() == null) ? 0 : getCreateTime().hashCode());
        result = prime * result + ((getModifyTime() == null) ? 0 : getModifyTime().hashCode());
        result = prime * result + ((getIsDeleted() == null) ? 0 : getIsDeleted().hashCode());
        return result;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getClass().getSimpleName());
        sb.append(" [");
        sb.append("Hash = ").append(hashCode());
        sb.append(", id=").append(id);
        sb.append(", nickname=").append(nickname);
        sb.append(", wxSvcOpenId=").append(wxSvcOpenId);
        sb.append(", wxOpenId=").append(wxOpenId);
        sb.append(", alipayName=").append(alipayName);
        sb.append(", alipayAccount=").append(alipayAccount);
        sb.append(", balance=").append(balance);
        sb.append(", freezeAmount=").append(freezeAmount);
        sb.append(", version=").append(version);
        sb.append(", createTime=").append(createTime);
        sb.append(", modifyTime=").append(modifyTime);
        sb.append(", isDeleted=").append(isDeleted);
        sb.append(", serialVersionUID=").append(serialVersionUID);
        sb.append("]");
        return sb.toString();
    }
}