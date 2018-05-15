package org.rx.lr.web;

import org.rx.lr.repository.model.common.PagedResult;
import org.rx.lr.service.UserService;
import org.rx.lr.web.dto.user.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/user", method = RequestMethod.POST)
public class UserController {
    @Autowired
    private UserService service;

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
