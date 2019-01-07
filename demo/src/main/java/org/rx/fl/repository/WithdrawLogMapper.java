package org.rx.fl.repository;

import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.rx.fl.repository.model.WithdrawLog;
import org.rx.fl.repository.model.WithdrawLogExample;

public interface WithdrawLogMapper {
    long sumAmount(WithdrawLogExample example);

    long countByExample(WithdrawLogExample example);

    int deleteByExample(WithdrawLogExample example);

    int deleteByPrimaryKey(String id);

    int insert(WithdrawLog record);

    int insertSelective(WithdrawLog record);

    List<WithdrawLog> selectByExample(WithdrawLogExample example);

    WithdrawLog selectByPrimaryKey(String id);

    int updateByExampleSelective(@Param("record") WithdrawLog record, @Param("example") WithdrawLogExample example);

    int updateByExample(@Param("record") WithdrawLog record, @Param("example") WithdrawLogExample example);

    int updateByPrimaryKeySelective(WithdrawLog record);

    int updateByPrimaryKey(WithdrawLog record);
}