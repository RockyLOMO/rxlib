package org.rx.lr.service;

import org.rx.lr.repository.IRepository;
import org.rx.lr.repository.model.User;
import org.rx.lr.service.mapper.UserMapper;
import org.rx.lr.web.dto.user.SignInRequest;
import org.rx.lr.web.dto.user.SignUpRequest;
import org.rx.security.MD5Util;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class UserService {
    @Autowired
    private IRepository<User> repository;

    public User signUp(SignUpRequest request) {
        User user = UserMapper.INSTANCE.toUser(request);
        user.setPassword(hexPwd(user.getPassword()));
        repository.save(user);
        return user;
    }

    public User signIn(SignInRequest request) {
        User user = repository.query(p -> p.getUserName().equals(request.getUserName()) && p.getPassword().equals(hexPwd(request.getPassword()))).firstOrDefault();
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        return user;
    }

    private String hexPwd(String pwd) {
        return MD5Util.md5Hex(pwd);
    }
}
