package org.rx.fl.repository;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.rx.fl.repository.model.UserGoods;
import org.rx.fl.repository.model.UserGoodsExample;

public interface UserGoodsMapper {
    long countByExample(UserGoodsExample example);

    int deleteByExample(UserGoodsExample example);

    int deleteByPrimaryKey(String id);

    int insert(UserGoods record);

    int insertSelective(UserGoods record);

    List<UserGoods> selectByExample(UserGoodsExample example);

    UserGoods selectByPrimaryKey(String id);

    int updateByExampleSelective(@Param("record") UserGoods record, @Param("example") UserGoodsExample example);

    int updateByExample(@Param("record") UserGoods record, @Param("example") UserGoodsExample example);

    int updateByPrimaryKeySelective(UserGoods record);

    int updateByPrimaryKey(UserGoods record);
}