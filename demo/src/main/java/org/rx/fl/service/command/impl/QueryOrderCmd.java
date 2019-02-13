package org.rx.fl.service.command.impl;

import lombok.Getter;
import lombok.Setter;
import org.rx.beans.DateTime;
import org.rx.common.App;
import org.rx.common.NQuery;
import org.rx.fl.dto.repo.OrderResult;
import org.rx.fl.dto.repo.QueryOrdersParameter;
import org.rx.fl.service.OrderService;
import org.rx.fl.service.command.Command;
import org.rx.fl.service.command.HandleResult;
import org.springframework.context.annotation.Scope;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

import java.util.List;

import static org.rx.common.Contract.require;
import static org.rx.fl.util.DbUtil.toMoney;

@Order(7)
@Component
@Scope("prototype")
public class QueryOrderCmd implements Command {
    @Resource
    private OrderService orderService;
    @Getter
    @Setter
    private int step = 1;

    @Override
    public boolean peek(String message) {
        require(message);
        message = message.trim();

        return NQuery.of("查询订单", "5").contains(message);
    }

    @Override
    public HandleResult<String> handleMessage(String userId, String message) {
        require(userId, message);

        switch (step) {
            case 1:
                step = 2;
                return HandleResult.ok("一一一一查 询 订 单一一一一\n" +
                        "[1]---------3天内订单\n" +
                        "[2]---------4-7天内订单\n" +
                        "[3]---------8-15天内订单\n" +
                        "\n" +
                        "    亲，请输入[ ]内的数字序号或订单号查询。", this);
            case 2:
                StringBuilder out = new StringBuilder();
                QueryOrdersParameter parameter = new QueryOrdersParameter();
                parameter.setUserId(userId);
                DateTime now = DateTime.now().getDateComponent();
                switch (message.trim()) {
                    case "1":
                        parameter.setStartTime(now.addDays(-3));
                        parameter.setEndTime(now.addDays(1));
                        out.append("一一一一订 单 详 细一一一一\n" +
                                "最近3天内订单:\n\n");
                        break;
                    case "2":
                        parameter.setStartTime(now.addDays(-7));
                        parameter.setEndTime(now.addDays(-3));
                        out.append("一一一一订 单 详 细一一一一\n" +
                                "最近4-7天内订单:\n\n");
                        break;
                    case "3":
                        parameter.setStartTime(now.addDays(-8));
                        parameter.setEndTime(now.addDays(-14));
                        out.append("一一一一订 单 详 细一一一一\n" +
                                "最近8-15天内订单:\n\n");
                        break;
                    default:
                        String orderNo = message.trim();
                        parameter.setOrderNo(orderNo);
                        out.append(String.format("一一一一订 单 详 细一一一一\n" +
                                "订单号: %s 查询:\n\n", orderNo));
                        break;
                }
                renderOrder(parameter, out);
                return HandleResult.ok(out.toString());
        }
        return HandleResult.fail();
    }

    private void renderOrder(QueryOrdersParameter parameter, StringBuilder out) {
        List<OrderResult> orders = orderService.queryOrders(parameter);
        NQuery<NQuery<OrderResult>> nQuery = NQuery.of(orders).groupBy(p -> p.getOrderNo(), p -> p.right).orderBy(p -> p.first().getCreateTime());
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
    }
}
