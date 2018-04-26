package org.rx.lr.web;

import org.rx.lr.repository.UserRepository;
import org.rx.lr.repository.model.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping(method = RequestMethod.POST)
public class UserController {
    @Autowired
    private UserRepository userRepository;

    @RequestMapping(value = "/signUp")
    public User signUp(@RequestBody User user) {
        return userRepository.signUp(user);
    }

    @RequestMapping(value = "/add")
    public int add(int a, int b) {
        return a + b;
    }
}
