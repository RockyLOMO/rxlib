package org.rx.lr.web;

import org.rx.lr.repository.model.User;
import org.rx.lr.service.UserService;
import org.rx.lr.web.dto.user.SignInRequest;
import org.rx.lr.web.dto.user.SignUpRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/user", method = RequestMethod.POST)
public class UserController {
    @Autowired
    private UserService service;

    @RequestMapping(value = "/signUp")
    public User signUp(@RequestBody SignUpRequest request) {
        return service.signUp(request);
    }

    @RequestMapping(value = "/signIn")
    public User signIn(@RequestBody SignInRequest request) {
        return service.signIn(request);
    }

    @RequestMapping(value = "/add")
    public int add(int a, int b) {
        return a + b;
    }
}
