package org.rx.fl.service.command.impl;

import com.google.common.base.Strings;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.rx.common.MediaConfig;
import org.rx.fl.service.command.Command;
import org.rx.fl.service.command.HandleResult;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import static org.rx.common.Contract.require;

@Order(19)
@Component
@Slf4j
public class AliPayCmd implements Command {
    private String start = "搜“", end = "”领红包";
    @Getter
    private String sourceMessage;
    private String code;

    //打开支付宝首页搜“546267657”领红包，领到大红包的小伙伴赶紧使用哦！
    @Override
    public boolean peek(String message) {
        require(message);
        message = message.trim();

        return message.contains(start) && message.contains(end);
    }

    @Override
    public HandleResult<String> handleMessage(String userId, String message) {
        if (Strings.isNullOrEmpty(code)) {
            if (!MediaConfig.RxId.equalsIgnoreCase(userId)) {
                return HandleResult.ok("");
            }
            int s = message.indexOf(start), e = message.indexOf(end, s);
            if (s == -1 || e == -1) {
                return HandleResult.ok("");
            }
            sourceMessage = message;
            code = message.substring(s + start.length(), e);
        }
        return HandleResult.ok(String.format("\n--------------------------------\n" +
                "【红包】福利来袭【红包】\n" +
                "支富宝搜数字：“%s”，即可领紅包\n" +
                "注意，领到就可以花了哦", code));
    }
}
