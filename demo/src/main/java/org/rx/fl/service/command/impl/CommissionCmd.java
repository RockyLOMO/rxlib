package org.rx.fl.service.command.impl;

import lombok.extern.slf4j.Slf4j;
import org.rx.common.SystemException;
import org.rx.fl.dto.bot.BotType;
import org.rx.fl.dto.bot.OpenIdInfo;
import org.rx.fl.service.NotifyService;
import org.rx.fl.service.command.Command;
import org.rx.fl.service.command.HandleResult;
import org.rx.fl.service.user.UserService;
import org.springframework.context.annotation.Scope;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

import static org.rx.common.Contract.require;

@Order(2)
@Component
@Scope("prototype")
@Slf4j
public class CommissionCmd implements Command {
    @Resource
    private UserService userService;
    @Resource
    private NotifyService notifyService;
    private String parentUserId;

    @Override
    public boolean peek(String message) {
        require(message);
        message = message.trim();
        if (message.length() > 22) {
            return false;
        }

        OpenIdInfo openId = new OpenIdInfo();
        openId.setBotType(BotType.Wx);
        openId.setOpenId(message);
        parentUserId = userService.getUserId(openId, false);
        return parentUserId != null;
    }

    @Override
    public HandleResult<String> handleMessage(String userId, String message) {
        require(userId, message);

        try {
            userService.bindRelation(userId, parentUserId);
            OpenIdInfo openId = userService.getOpenId(userId, BotType.Wx);
            OpenIdInfo parentOpenId = userService.getOpenId(parentUserId, BotType.Wx);
            notifyService.add(parentUserId, String.format("一一一一绑 定 成 功一一一一\n" +
                    "%s与您已绑定成为伙伴～", openId.getOpenId()));
            return HandleResult.ok(String.format("一一一一绑 定 成 功一一一一\n" +
                    "您与%s已绑定成为伙伴～", parentOpenId.getOpenId()));
        } catch (SystemException e) {
            log.warn("CommissionCmd", e);
            return HandleResult.ok("一一一一绑 定 失 败一一一一\n" + e.getFriendlyMessage());
        }
    }
}
