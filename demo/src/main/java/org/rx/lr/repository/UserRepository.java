package org.rx.lr.repository;

import org.rx.lr.repository.model.User;
import org.springframework.stereotype.Component;

@Component
public class UserRepository extends Repository {
    public User signUp(User user) {
        getDb().store(user);
        return user;
    }
}
