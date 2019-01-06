//package org.rx.fl.service.command.impl;
//
//import lombok.Data;
//import org.rx.fl.service.command.Command;
//import org.rx.fl.service.command.HandleResult;
//
//public class UserDashboardCmd implements Command<UserDashboardCmd.Argument> {
//    @Data
//    public static class Argument {
//        private double totalWithdrawAmount;
//        private double balance;
//        private double freezeAmount;
//        private double unconfirmedAmount;
//        private int totalConfirmedOrderCount;
//        private int checkInCount;
//        private double checkInAmount;
//        private double withdrawAmount;
//    }
//
//    @Override
//    public HandleResult<String> handleMessage(String message, UserDashboardCmd.Argument argument) {
//        return HandleResult.of(String.format("一一一一个 人 信 息一一一一\n" +
//                        "总提现金额: %.2f元\n" +
//                        "可提现金额: %.2f元\n" +
//                        "  冻结金额: %.2f元\n" +
//                        "未收货金额: %.2f元\n" +
//                        "总成功订单: %s单\n" +
//                        "\n" +
//                        "签到次数: %s次\n" +
//                        "签到奖励: %.2f元\n" +
//                        "  提现中: %.2f元", argument.getTotalWithdrawAmount(), argument.getBalance(),
//                argument.getFreezeAmount(), argument.getUnconfirmedAmount(), argument.getTotalConfirmedOrderCount(),
//                argument.getCheckInCount(), argument.getCheckInAmount(), argument.getWithdrawAmount()));
//    }
//}
