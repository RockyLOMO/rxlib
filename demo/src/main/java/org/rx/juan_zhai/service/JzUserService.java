package org.rx.juan_zhai.service;

import org.rx.Logger;
import org.rx.NQuery;
import org.rx.SystemException;
import org.rx.bean.DateTime;
import org.rx.juan_zhai.repository.model.CheckInLog;
import org.rx.juan_zhai.repository.model.User;
import org.rx.juan_zhai.repository.model.UserComment;
import org.rx.juan_zhai.service.mapper.JzUserMapper;
import org.rx.juan_zhai.web.dto.user.*;
import org.rx.repository.IRepository;
import org.rx.repository.PagedResult;
import org.rx.security.MD5Util;
import org.rx.util.validator.EnableValid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.validation.constraints.NotNull;
import java.util.function.Predicate;

@EnableValid
@Service
public class JzUserService {
    static final String DefaultPassword = "rx4660";
    @Autowired
    private IRepository<User> userIRepository;
    @Autowired
    private IRepository<UserComment> userCommentIRepository;
    @Autowired
    private IRepository<CheckInLog> checkInLogIRepository;
    @Autowired
    private SmsService smsService;

    public void saveComment(@NotNull SaveUserCommentRequest request) {
        UserComment userComment = JzUserMapper.INSTANCE.toUserComment(request);
        if (userComment.getUserId() == null) {
            SignUpRequest sr = new SignUpRequest();
            sr.setUserName(request.getMobile());
            sr.setMobile(request.getMobile());
            sr.setSmsCode(request.getSmsCode());
            UserResponse userResponse = signUp(sr);
            Logger.info("saveComment signUp=%s", userResponse);
            userComment.setUserId(userResponse.getUserId());
        }
        userCommentIRepository.save(userComment);
    }

    public PagedResult<UserCommentResponse> queryUserComments(@NotNull QueryUserCommentsRequest request) {
        Predicate<UserComment> condition;
        if (request.getMobile() != null) {
            User user = userIRepository.single(p -> p.getUserName().equals(request.getMobile()));
            condition = p -> p.getUserId().equals(user.getId());
        } else {
            condition = p -> true;
        }
        return userCommentIRepository.pageDescending(condition, p -> p.getCreateTime(), request).convert(p -> {
            UserCommentResponse response = JzUserMapper.INSTANCE.toUserCommentResponse(p);
            User user = userIRepository.single(p.getUserId());
            response.setMobile(user.getMobile());
            return response;
        });
    }

    public UserResponse signUp(@NotNull SignUpRequest request) {
        User user = JzUserMapper.INSTANCE.toUser(request);
        if (!userIRepository.list(p -> p.getUserName().equals(user.getUserName())).isEmpty()) {
            throw SystemException.wrap(new IllegalArgumentException("用户名已存在"));
        }
        if (request.getSmsCode() != null) {
            smsService.validCode(request.getUserName(), request.getSmsCode());
            if (user.getPassword() == null) {
                user.setPassword(DefaultPassword);
            }
        }

        user.setPassword(hexPwd(user.getPassword()));
        userIRepository.save(user);
        return JzUserMapper.INSTANCE.toUserResponse(user);
    }

    public UserResponse signIn(@NotNull SignInRequest request) {
        User user = NQuery.of(userIRepository.list(p -> p.getUserName().equals(request.getUserName())
                && p.getPassword().equals(hexPwd(request.getPassword())))).firstOrDefault();
        if (user == null) {
            throw SystemException.wrap(new IllegalArgumentException("用户名或密码错误"));
        }
        return JzUserMapper.INSTANCE.toUserResponse(user);
    }

    private String hexPwd(String pwd) {
        return MD5Util.md5Hex(pwd);
    }

    public PagedResult<UserResponse> queryUsers(@NotNull QueryUsersRequest request) {
        return userIRepository.pageDescending(p -> (request.getUserName() == null || p.getUserName().equals(request.getUserName()))
                && (request.getUserId() == null || p.getId().equals(request.getUserId())), p -> p.getCreateTime(), request)
                .convert(p -> JzUserMapper.INSTANCE.toUserResponse(p));
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

        CheckInLog checkIn = JzUserMapper.INSTANCE.toCheckInLog(request);
        checkInLogIRepository.save(checkIn);
    }
}
