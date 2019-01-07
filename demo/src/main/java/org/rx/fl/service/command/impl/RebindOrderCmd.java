//package org.rx.fl.service.command.impl;
//
//import lombok.Getter;
//import lombok.Setter;
//import lombok.extern.slf4j.Slf4j;
//import org.rx.SystemException;
//import org.rx.fl.service.OrderService;
//import org.rx.fl.service.command.Command;
//import org.rx.fl.service.command.HandleResult;
//import org.springframework.context.annotation.Scope;
//import org.springframework.stereotype.Component;
//
//import javax.annotation.Resource;
//
//import static org.rx.Contract.require;
//
//@Component
//@Scope("prototype")
//@Slf4j
//public class RebindOrderCmd implements Command {
//    @Resource
//    private OrderService orderService;
//    @Getter
//    @Setter
//    private int step = 1;
//
//    @Override
//    public boolean peek(String message) {
//        require(message);
//        message = message.trim();
//
//        return message.equals("订单绑定");
//    }
//
//    @Override
//    public HandleResult<String> handleMessage(String userId, String message) {
//        switch (step) {
//            case 1:
//                step = 2;
//                return HandleResult.of("一一一一订 单 绑 定一一一一\n" +
//                        "    付款成功后如果2分钟内没有订单记录再发送订单编号绑定，订单编号在购买的商品详细页能查看到。", this);
//            case 2:
//                try {
//                    orderService.rebindOrder(userId, message);
//
//                } catch (SystemException e) {
//                    log.warn("RebindOrderCmd", e);
//                }
//        }
//    }
//}
