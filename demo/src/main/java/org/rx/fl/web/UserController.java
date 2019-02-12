package org.rx.fl.web;

import org.rx.fl.dto.bot.OpenIdInfo;
import org.rx.fl.dto.repo.WithdrawStatus;
import org.rx.fl.service.UserService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping(value = "user", method = {RequestMethod.POST, RequestMethod.GET})
public class UserController {
    @Resource
    private UserService userService;

    @RequestMapping("/correctBalance")
    public String correctBalance(OpenIdInfo openId, String sourceId, long money, String remark) {
        return userService.correctBalance(openId, sourceId, money, remark);
    }

    @RequestMapping("/processWithdraw")
    public String processWithdraw(OpenIdInfo openId, String withdrawId, WithdrawStatus status, String remark) {
        return userService.processWithdraw(openId, withdrawId, status, remark);
    }
}
