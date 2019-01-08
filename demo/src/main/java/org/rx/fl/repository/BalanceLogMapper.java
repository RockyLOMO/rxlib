package org.rx.fl.repository;

import org.rx.fl.repository.model.BalanceLog;
import org.rx.fl.repository.model.BalanceLogExample;

/**
 * BalanceLogMapper继承基类
 */
public interface BalanceLogMapper extends MyBatisBaseDao<BalanceLog, String, BalanceLogExample> {
    long sumAmount(BalanceLogExample example);
}
