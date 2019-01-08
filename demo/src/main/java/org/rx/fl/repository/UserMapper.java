package org.rx.fl.repository;

import org.rx.fl.repository.model.User;
import org.rx.fl.repository.model.UserExample;

/**
 * UserMapper继承基类
 */
public interface UserMapper extends MyBatisBaseDao<User, String, UserExample> {
}