package org.rx.fl.repository;

import org.rx.fl.repository.model.UserGoods;
import org.rx.fl.repository.model.UserGoodsExample;

import java.util.List;

/**
 * UserGoodsMapper继承基类
 */
public interface UserGoodsMapper extends MyBatisBaseDao<UserGoods, String, UserGoodsExample> {
    List<String> selectUserIdByGoods(UserGoods record);
}
