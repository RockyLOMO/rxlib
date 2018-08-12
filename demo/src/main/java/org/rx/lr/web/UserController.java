package org.rx.lr.web;

import org.rx.lr.repository.model.common.PagedResult;
import org.rx.lr.service.SmsService;
import org.rx.lr.service.UserService;
import org.rx.lr.web.dto.user.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/user", method = RequestMethod.POST)
public class UserController {
    @Autowired
    private UserService service;
    @Autowired
    private SmsService smsService;

    @RequestMapping(value = "/sendCode")
    public void sendCode(String mobile, SmsService.CodeType type) {
        smsService.sendCode(mobile, type);
    }

    @RequestMapping(value = "/saveComment")
    public void saveComment(@RequestBody SaveUserCommentRequest request) {
        service.saveComment(request);
    }

    @RequestMapping(value = "/queryUserComments")
    public PagedResult<UserCommentResponse> queryUserComments(@RequestBody QueryUserCommentsRequest request) {
        return service.queryUserComments(request);
    }

    @RequestMapping(value = "/signUp")
    public UserResponse signUp(@RequestBody SignUpRequest request) {
        return service.signUp(request);
    }

    @RequestMapping(value = "/signIn")
    public UserResponse signIn(@RequestBody SignInRequest request) {
        return service.signIn(request);
    }

    @RequestMapping(value = "/queryUsers")
    public PagedResult<UserResponse> queryUsers(@RequestBody QueryUsersRequest request) {
        return service.queryUsers(request);
    }

    @RequestMapping(value = "/checkIn")
    public void checkIn(@RequestBody CheckInRequest request) {
        service.checkIn(request);
    }
}
