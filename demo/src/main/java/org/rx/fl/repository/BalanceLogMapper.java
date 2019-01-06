package org.rx.fl.repository;

import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.rx.fl.repository.model.BalanceLog;
import org.rx.fl.repository.model.BalanceLogExample;

public interface BalanceLogMapper {
    long sumAmount(BalanceLogExample example);

    long countByExample(BalanceLogExample example);

    int deleteByExample(BalanceLogExample example);

    int deleteByPrimaryKey(String id);

    int insert(BalanceLog record);

    int insertSelective(BalanceLog record);

    List<BalanceLog> selectByExample(BalanceLogExample example);

    BalanceLog selectByPrimaryKey(String id);

    int updateByExampleSelective(@Param("record") BalanceLog record, @Param("example") BalanceLogExample example);

    int updateByExample(@Param("record") BalanceLog record, @Param("example") BalanceLogExample example);

    int updateByPrimaryKeySelective(BalanceLog record);

    int updateByPrimaryKey(BalanceLog record);
}
