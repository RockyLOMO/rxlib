package org.rx.fl.service;

import lombok.SneakyThrows;
import org.rx.common.App;
import org.rx.common.NQuery;
import org.rx.fl.dto.bot.MessageInfo;
import org.rx.fl.dto.bot.OpenIdInfo;
import org.rx.fl.dto.repo.UserInfo;
import org.rx.fl.repository.model.Commission;
import org.rx.fl.repository.model.Order;
import org.rx.fl.service.command.impl.CommissionCmd;
import org.rx.fl.service.order.NotifyOrdersInfo;
import org.rx.fl.service.user.UserService;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.rx.common.Contract.require;
import static org.rx.fl.util.DbUtil.toMoney;
import static org.rx.util.AsyncTask.TaskFactory;

@Service
public class NotifyService {
    @Resource
    private BotService botService;
    @Resource
    private UserService userService;
    private final ConcurrentLinkedQueue<List<MessageInfo>> queue;

    public NotifyService() {
        queue = new ConcurrentLinkedQueue<>();
        TaskFactory.schedule(this::push, 2 * 1000);
    }

    @SneakyThrows
    private void push() {
        List<MessageInfo> messages;
        while ((messages = queue.poll()) != null) {
            botService.pushMessages(messages);
        }
        Thread.sleep(200);
    }

    public void add(String userId, List<String> contents) {
        require(userId, contents);

        List<OpenIdInfo> openIds = App.getOrStore(String.format("NotifyService.getOpenIds(%s)", userId), k -> userService.getOpenIds(userId), App.CacheContainerKind.ObjectCache);
        List<MessageInfo> messages = NQuery.of(openIds).selectMany(p -> NQuery.of(contents).select(content -> {
            MessageInfo msg = new MessageInfo();
            msg.setBotType(p.getBotType());
            msg.setOpenId(p.getOpenId());
            msg.setNickname(p.getNickname());
            msg.setContent(content);
            return msg;
        }).toList()).toList();
        queue.add(messages);
    }

    public void add(NotifyOrdersInfo notifyInfo) {
        require(notifyInfo);

        for (Order paidOrder : notifyInfo.paidOrders) {
            UserInfo user = userService.queryUser(paidOrder.getUserId());
            add(user.getUserId(), Arrays.asList(String.format("一一一一支 付 成 功一一一一\n" +
                            "%s\n" +
                            "订单编号:\n" +
                            "%s\n" +
                            "付费金额: %.2f元\n" +
                            "红包补贴: %.2f元\n" +
                            "\n" +
                            "可提现金额: %.2f元\n" +
                            "未收货金额: %.2f元\n" +
                            "-------------------------------\n" +
                            "亲 确认收货成功后，回复 提现 两个字，给你补贴红包\n" +
                            "\n" +
                            CommissionCmd.codeFormat, paidOrder.getGoodsName(), paidOrder.getOrderNo(),
                    toMoney(paidOrder.getPayAmount()), toMoney(paidOrder.getRebateAmount()),
                    toMoney(user.getBalance()), toMoney(user.getUnconfirmedOrderAmount())),
                    CommissionCmd.getCode(user.getUserId())));
        }
        for (Order settleOrder : notifyInfo.settleOrders) {
            UserInfo user = userService.queryUser(settleOrder.getUserId());
            add(user.getUserId(), Collections.singletonList(String.format("一一一一收 货 成 功一一一一\n" +
                            "%s\n" +
                            "订单编号:\n" +
                            "%s\n" +
                            "付费金额: %.2f元\n" +
                            "红包补贴: %.2f元\n" +
                            "\n" +
                            "可提现金额: %.2f元\n" +
                            "未收货金额: %.2f元\n" +
                            "总成功订单: %s单\n" +
                            "-------------------------------\n" +
                            "回复 提现 两个字，给你补贴红包\n" +
                            "补贴红包已转入可提现金额", settleOrder.getGoodsName(), settleOrder.getOrderNo(),
                    toMoney(settleOrder.getPayAmount()), toMoney(settleOrder.getSettleAmount()),
                    toMoney(user.getBalance()), toMoney(user.getUnconfirmedOrderAmount()), user.getConfirmedOrderCount())));
        }
        for (Order order : notifyInfo.restoreSettleOrder) {
            UserInfo user = userService.queryUser(order.getUserId());
            add(user.getUserId(), Collections.singletonList(String.format("一一一一退 货 成 功一一一一\n" +
                            "%s\n" +
                            "订单编号:\n" +
                            "%s\n" +
                            "付费金额: %.2f元\n" +
                            "红包补贴: %.2f元\n" +
                            "\n" +
                            "可提现金额: %.2f元\n" +
                            "未收货金额: %.2f元\n" +
                            "总成功订单: %s单\n" +
                            "-------------------------------\n" +
                            "补贴红包已从可提现金额扣除", order.getGoodsName(), order.getOrderNo(),
                    toMoney(order.getPayAmount()), toMoney(order.getSettleAmount()),
                    toMoney(user.getBalance()), toMoney(user.getUnconfirmedOrderAmount()), user.getConfirmedOrderCount())));
        }
        for (Commission commission : notifyInfo.paidCommissionOrders) {
            add(commission.getUserId(), Collections.singletonList(String.format("一一一一伙 伴 支 付一一一一\n" +
                    "亲，您的伙伴已下单，伙伴确认收货后可收到红包补贴约%.2f元", toMoney(commission.getAmount()))));
        }
        for (Commission commission : notifyInfo.settleCommissionOrders) {
            add(commission.getUserId(), Collections.singletonList(String.format("一一一一伙 伴 收 货一一一一\n" +
                    "亲，您的伙伴已收货，红包补贴%.2f元已转入可提现金额", toMoney(commission.getAmount()))));
        }
    }
}