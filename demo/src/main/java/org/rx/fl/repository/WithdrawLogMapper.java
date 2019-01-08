package org.rx.fl.repository;

import org.rx.fl.repository.model.WithdrawLog;
import org.rx.fl.repository.model.WithdrawLogExample;

/**
 * WithdrawLogMapper继承基类
 */
public interface WithdrawLogMapper extends MyBatisBaseDao<WithdrawLog, String, WithdrawLogExample> {
    long sumAmount(WithdrawLogExample example);
}
