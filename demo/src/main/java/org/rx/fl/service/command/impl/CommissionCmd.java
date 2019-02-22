package org.rx.fl.service.command.impl;

import lombok.extern.slf4j.Slf4j;
import org.rx.common.NQuery;
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

import java.util.Collections;
import java.util.UUID;

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
    private UUID code;

    @Override
    public boolean peek(String message) {
        require(message);
        message = message.trim();

        try {
            code = UUID.fromString(message);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public HandleResult<String> handleMessage(String userId, String message) {
        require(userId, message);

        String parentUserId = code.toString();
        try {
            userService.bindRelation(userId, parentUserId);
            OpenIdInfo openId = NQuery.of(userService.getOpenIds(userId)).where(p -> p.getBotType() == BotType.Wx).first();
            OpenIdInfo pOpenId = NQuery.of(userService.getOpenIds(parentUserId)).where(p -> p.getBotType() == BotType.Wx).first();
            notifyService.add(pOpenId.getOpenId(), Collections.singletonList(String.format("一一一一绑 定 成 功一一一一\n" +
                    "%s与您已绑定成为伙伴～", openId.getOpenId())));
            return HandleResult.ok(String.format("一一一一绑 定 成 功一一一一\n" +
                    "您与%s已绑定成为伙伴～", pOpenId.getOpenId()));
        } catch (SystemException e) {
            log.warn("CommissionCmd", e);
            return HandleResult.ok("一一一一绑 定 失 败一一一一\n" + e.getFriendlyMessage());
        }
    }
}
