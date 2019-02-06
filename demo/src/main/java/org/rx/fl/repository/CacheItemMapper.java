package org.rx.fl.repository;

import org.rx.fl.repository.model.CacheItem;
import org.rx.fl.repository.model.CacheItemExample;
import org.springframework.stereotype.Repository;

/**
 * CacheItemMapper继承基类
 */
@Repository
public interface CacheItemMapper extends MyBatisBaseDao<CacheItem, String, CacheItemExample> {
}