package org.rx.fl.repository;

import org.rx.fl.repository.model.Commission;
import org.rx.fl.repository.model.CommissionExample;

/**
 * CommissionMapper继承基类
 */
public interface CommissionMapper extends MyBatisBaseDao<Commission, String, CommissionExample> {
}