package org.rx.fl.repository;

import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.rx.fl.repository.model.Order;
import org.rx.fl.repository.model.OrderExample;

public interface OrderMapper {
    long sumRebateAmount(OrderExample example);

    long countByExample(OrderExample example);

    int deleteByExample(OrderExample example);

    int deleteByPrimaryKey(String id);

    int insert(Order record);

    int insertSelective(Order record);

    List<Order> selectByExample(OrderExample example);

    Order selectByPrimaryKey(String id);

    int updateByExampleSelective(@Param("record") Order record, @Param("example") OrderExample example);

    int updateByExample(@Param("record") Order record, @Param("example") OrderExample example);

    int updateByPrimaryKeySelective(Order record);

    int updateByPrimaryKey(Order record);
}