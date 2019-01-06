package org.rx.fl.repository;

import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.rx.fl.repository.model.CheckInLog;
import org.rx.fl.repository.model.CheckInLogExample;

public interface CheckInLogMapper {
    long sumBonus(CheckInLogExample example);

    long countByExample(CheckInLogExample example);

    int deleteByExample(CheckInLogExample example);

    int deleteByPrimaryKey(String id);

    int insert(CheckInLog record);

    int insertSelective(CheckInLog record);

    List<CheckInLog> selectByExample(CheckInLogExample example);

    CheckInLog selectByPrimaryKey(String id);

    int updateByExampleSelective(@Param("record") CheckInLog record, @Param("example") CheckInLogExample example);

    int updateByExample(@Param("record") CheckInLog record, @Param("example") CheckInLogExample example);

    int updateByPrimaryKeySelective(CheckInLog record);

    int updateByPrimaryKey(CheckInLog record);
}