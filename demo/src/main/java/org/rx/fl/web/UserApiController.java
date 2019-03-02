package org.rx.fl.web;

import org.rx.common.NQuery;
import org.rx.common.UserConfig;
import org.rx.fl.dto.bot.OpenIdInfo;
import org.rx.fl.dto.repo.WithdrawStatus;
import org.rx.fl.service.user.UserService;
import org.rx.util.validator.EnableValid;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.validation.constraints.NotNull;

@EnableValid
@RestController
@RequestMapping(value = "user", method = {RequestMethod.POST})
public class UserApiController {
    @Resource
    private UserConfig userConfig;
    @Resource
    private UserService userService;

    @RequestMapping("/inviteConfirm")
    public void inviteConfirm() {

    }

    @RequestMapping("/feedback")
    public void feedback(String userId, @NotNull String content) {
        if (userId == null) {
            userId = NQuery.of(userConfig.getAdminIds()).firstOrDefault();
            if (userId == null) {
                return;
            }
        }
        userService.feedback(userId, content);
    }

    @RequestMapping("/correctBalance")
    public String correctBalance(OpenIdInfo openId, String sourceId, long money, String remark) {
        return userService.correctBalance(openId, sourceId, money, remark);
    }

    @RequestMapping("/processWithdraw")
    public String processWithdraw(OpenIdInfo openId, String withdrawId, WithdrawStatus status, String remark) {
        return userService.processWithdraw(openId, withdrawId, status, remark);
    }
}
