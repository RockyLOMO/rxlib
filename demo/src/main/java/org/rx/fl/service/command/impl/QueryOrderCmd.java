package org.rx.fl.service.command.impl;

import org.rx.beans.DateTime;
import org.rx.beans.Tuple;
import org.rx.common.App;
import org.rx.common.NQuery;
import org.rx.fl.dto.media.OrderInfo;
import org.rx.fl.dto.repo.OrderResult;
import org.rx.fl.service.OrderService;
import org.rx.fl.service.command.Command;
import org.rx.fl.service.command.HandleResult;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

import java.util.List;

import static org.rx.common.Contract.require;
import static org.rx.fl.util.DbUtil.toMoney;

@Component
public class QueryOrderCmd implements Command {
    @Resource
    private OrderService orderService;

    @Override
    public boolean peek(String message) {
        require(message);
        message = message.trim();

        return NQuery.of("查询订单", "6").contains(message);
    }

    @Override
    public HandleResult<String> handleMessage(String userId, String message) {
        require(userId, message);

        List<OrderResult> orders = orderService.queryOrders(userId, 20);
        StringBuilder out = new StringBuilder("一一一一订 单 详 细一一一一\n" +
                "最近20笔订单详细:\n\n");
        NQuery<NQuery<OrderResult>> nQuery = NQuery.of(orders).groupBy(p -> p.getOrderNo(), p -> p.right).orderByDescending(p -> p.first().getCreateTime());
        for (NQuery<OrderResult> orderResults : nQuery) {
            OrderResult order = orderResults.first();
            out.append(String.format("[%s]  %s\n" +
                            "订单号: %s\n", order.getMediaType().toDescription(),
                    new DateTime(order.getCreateTime()).toDateTimeString(),
                    App.filterPrivacy(order.getOrderNo())));
            for (OrderResult goods : orderResults) {
                out.append(String.format("一一一一一一一一\n" +
                                "商品: %s\n" +
                                "返利金额: %.2f元  状态: %s\n", goods.getGoodsName(),
                        toMoney(goods.getRebateAmount()),
                        goods.getStatus().toDescription()));
            }
            out.append("\n\n");
        }
//        for (OrderResult order : orders) {
//            out.append(String.format("%s  已%s\n" +
//                            "%s %s\n" +
//                            "%s\n" +
//                            "返利金额: %.2f元\n" +
//                            "\n", new DateTime(order.getCreateTime()).toDateTimeString(), order.getStatus().toDescription(),
//                    order.getMediaType().toDescription(), App.filterPrivacy(order.getOrderNo()),
//                    order.getGoodsName(), toMoney(order.getRebateAmount())));
//        }
        return HandleResult.of(out.toString());
    }
}
