package org.rx.fl.repository;

import org.rx.fl.repository.model.Order;
import org.rx.fl.repository.model.OrderExample;

/**
 * OrderMapper继承基类
 */
public interface OrderMapper extends MyBatisBaseDao<Order, String, OrderExample> {
    long sumRebateAmount(OrderExample example);
}
