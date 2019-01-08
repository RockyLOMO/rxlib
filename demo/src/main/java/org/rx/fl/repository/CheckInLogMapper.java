package org.rx.fl.repository;

import org.rx.fl.repository.model.CheckInLog;
import org.rx.fl.repository.model.CheckInLogExample;

/**
 * CheckInLogMapper继承基类
 */
public interface CheckInLogMapper extends MyBatisBaseDao<CheckInLog, String, CheckInLogExample> {
    long sumBonus(CheckInLogExample example);
}
