package org.rx.lr.service;

import org.rx.NQuery;
import org.rx.SystemException;
import org.rx.bean.DateTime;
import org.rx.lr.repository.IRepository;
import org.rx.lr.repository.model.CheckInLog;
import org.rx.lr.repository.model.User;
import org.rx.lr.repository.model.common.PagedResult;
import org.rx.lr.service.mapper.UserMapper;
import org.rx.lr.web.dto.user.CheckInRequest;
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
    private IRepository<User> userIRepository;
    @Autowired
    private IRepository<CheckInLog> checkInLogIRepository;

    public User signUp(@NotNull SignUpRequest request) {
        User user = UserMapper.INSTANCE.toUser(request);
        user.setPassword(hexPwd(user.getPassword()));
        if (!userIRepository.list(p -> p.getUserName().equals(user.getUserName())).isEmpty()) {
            throw SystemException.wrap(new IllegalArgumentException("用户名已存在"));
        }

        userIRepository.save(user);
        return user;
    }

    public User signIn(@NotNull SignInRequest request) {
        User user = NQuery.of(userIRepository.list(p -> p.getUserName().equals(request.getUserName())
                && p.getPassword().equals(hexPwd(request.getPassword())))).firstOrDefault();
        if (user == null) {
            throw SystemException.wrap(new IllegalArgumentException("用户名或密码错误"));
        }
        return user;
    }

    private String hexPwd(String pwd) {
        return MD5Util.md5Hex(pwd);
    }

    public PagedResult<User> queryUsers(@NotNull QueryUsersRequest request) {
        return userIRepository.pageDescending(p -> request.getUserName() == null || p.getUserName().equals(request.getUserName()), p -> p.getCreateTime(), request);
    }

    public void checkIn(@NotNull CheckInRequest request) {
        User user = userIRepository.single(p -> p.getId().equals(request.getUserId()));
        if (user == null) {
            throw SystemException.wrap(new IllegalArgumentException("用户不存在"));
        }
        DateTime now = DateTime.now();
        DateTime start = new DateTime(now.getYear(), now.getMonth(), now.getDay()), end = start.addDays(1);
        if (!checkInLogIRepository.list(p -> p.getUserId().equals(user.getId()) && start.before(p.getCreateTime()) && p.getCreateTime().before(end)).isEmpty()) {
            throw SystemException.wrap(new IllegalArgumentException("该用户当天已签到"));
        }

        CheckInLog checkIn = UserMapper.INSTANCE.toCheckInLog(request);
        checkInLogIRepository.save(checkIn);
    }
}
