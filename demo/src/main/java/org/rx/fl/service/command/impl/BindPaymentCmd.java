package org.rx.fl.service.command.impl;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.rx.common.NQuery;
import org.rx.common.SystemException;
import org.rx.fl.service.UserService;
import org.rx.fl.service.command.Command;
import org.rx.fl.service.command.HandleResult;
import org.springframework.context.annotation.Scope;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

import static org.rx.common.Contract.require;

@Order(4)
@Component
@Scope("prototype")
@Slf4j
public class BindPaymentCmd implements Command {
    @Resource
    private FeedbackCmd feedbackCmd;
    @Resource
    private UserService userService;
    @Getter
    @Setter
    private int step = 1;

    @Override
    public boolean peek(String message) {
        require(message);
        message = message.trim();

        return NQuery.of("绑定", "2").contains(message);
    }

    @Override
    public HandleResult<String> handleMessage(String userId, String message) {
        require(userId, message);

        switch (step) {
            case 1:
                step = 2;
                return HandleResult.ok("一一一一绑 定 账 号一一一一\n" +
                        "亲，回复如下格式绑定:\n" +
                        "姓名***支付宝*********\n" +
                        "\n" +
                        "如:姓名小范支付宝15888888888\n" +
                        "\n" +
                        "    本绑定只用于提现到支付宝时使用，支付宝账号应为手机号码或者邮箱地址，如不清楚可打开支付宝APP查看自己的账号。", this);
            case 2:
                String k1 = "姓名", k2 = "支付宝";
                int s1 = message.indexOf(k1), s2 = message.indexOf(k2);
                if (!(s1 != -1 && s2 != -1 && s1 < s2)) {
                    return HandleResult.ok("一一一一绑 定 失 败一一一一\n" +
                            "亲，回复格式错误，请回复如下格式绑定:\n" +
                            "姓名***支付宝*********\n" +
                            "\n" +
                            "如:姓名小范支付宝15888888888", this);
                }
                try {
                    String name = message.substring(s1 + k1.length(), s2).trim(), account = message.substring(s2 + k2.length()).trim();
                    userService.bindPayment(userId, name, account);
                    return HandleResult.ok("一一一一绑 定 成 功一一一一\n" +
                            "    亲，您已成功绑定！");
                } catch (SystemException e) {
                    log.warn("BindPaymentCmd", e);
                    feedbackCmd.setStep(2);
                    return HandleResult.ok("一一一一绑 定 失 败一一一一\n" + e.getFriendlyMessage() +
                            "\n如果支付宝或姓名错误，请回复新的支付宝和姓名，系统会在24小时内为您处理。", feedbackCmd);
                }
        }
        return HandleResult.fail();
    }
}
