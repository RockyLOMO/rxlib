package org.rx.fl.service.command.impl;

import org.rx.fl.service.command.Command;
import org.rx.fl.service.command.HandleResult;
import org.springframework.stereotype.Component;

import static org.rx.Contract.require;

@Component
public class QueryOrderCmd implements Command {
    @Override
    public boolean peek(String message) {
        require(message);
        message = message.trim();

        return message.equals("查询订单");
    }

    @Override
    public HandleResult<String> handleMessage(String userId, String message) {

        return HandleResult.of("一一一一订 单 详 细一一一一\n" +
                "    R.El\n" +
                "最近交易订单详细:\n" +
                "时间:2018-12-28 10:48:40\n" +
                "淘宝3053****3943 交易成功\n" +
                "返利金额:1.83\n" +
                "\n" +
                "时间:2018-12-28 10:47:41\n" +
                "淘宝3060****3943 交易成功\n" +
                "返利金额:1.85\n" +
                "\n" +
                "时间:2018-12-25 11:09:02\n" +
                "淘宝3054****3943 交易成功\n" +
                "返利金额:0.12\n" +
                "\n" +
                "时间:2018-12-24 08:17:22\n" +
                "淘宝3042****3943 交易成功\n" +
                "返利金额:16.18\n" +
                "\n" +
                "时间:2018-12-23 08:55:24\n" +
                "淘宝3003****3943 交易成功\n" +
                "返利金额:0.56\n" +
                "\n" +
                "时间:2018-12-23 08:54:34\n" +
                "淘宝3003****3943 交易成功\n" +
                "返利金额:0.59\n" +
                "\n" +
                "时间:2018-12-23 01:38:43\n" +
                "淘宝3034****3943 交易成功\n" +
                "返利金额:3.82\n" +
                "\n" +
                "时间:2018-12-17 08:43:12\n" +
                "淘宝2860****3943 交易成功\n" +
                "返利金额:0.25\n" +
                "\n" +
                "时间:2018-12-12 08:46:34\n" +
                "淘宝2880****3943 交易成功\n" +
                "返利金额:0.49\n" +
                "\n" +
                "时间:2018-12-09 07:40:02\n" +
                "淘宝2851****3943 交易成功\n" +
                "返利金额:5.62\n" +
                "\n");
    }
}
