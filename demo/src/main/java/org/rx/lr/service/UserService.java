package org.rx.lr.service;

import org.rx.NQuery;
import org.rx.SystemException;
import org.rx.lr.repository.IRepository;
import org.rx.lr.repository.model.User;
import org.rx.lr.service.mapper.UserMapper;
import org.rx.lr.web.dto.common.PagedResponse;
import org.rx.lr.web.dto.user.QueryUsersRequest;
import org.rx.lr.web.dto.user.SignInRequest;
import org.rx.lr.web.dto.user.SignUpRequest;
import org.rx.security.MD5Util;
import org.rx.util.validator.EnableValid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.validation.constraints.NotNull;

@EnableValid
@Service
public class UserService {
    @Autowired
    private IRepository<User> repository;

    public User signUp(@NotNull SignUpRequest request) {
        User user = UserMapper.INSTANCE.toUser(request);
        user.setPassword(hexPwd(user.getPassword()));
        if (repository.query(p -> p.getUserName().equals(user.getUserName())).any()) {
            throw SystemException.wrap(new IllegalArgumentException("用户名已存在"));
        }

        repository.save(user);
        return user;
    }

    public User signIn(@NotNull SignInRequest request) {
        User user = repository.query(p -> p.getUserName().equals(request.getUserName())
                && p.getPassword().equals(hexPwd(request.getPassword()))).firstOrDefault();
        if (user == null) {
            throw SystemException.wrap(new IllegalArgumentException("用户名或密码错误"));
        }
        return user;
    }

    private String hexPwd(String pwd) {
        return MD5Util.md5Hex(pwd);
    }

    public PagedResponse<User> queryUsers(@NotNull QueryUsersRequest request) {
        NQuery<User> users = repository.queryDescending(p -> request.getUserName() == null || p.getUserName().equals(request.getUserName()), p -> p.getCreateTime());
        return request.page(users);
    }

    public void checkIn() {

    }
}
