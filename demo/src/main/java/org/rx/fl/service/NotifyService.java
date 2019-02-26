package org.rx.fl.service;

import com.google.common.base.Strings;
import lombok.SneakyThrows;
import org.rx.beans.$;
import org.rx.beans.DateTime;
import org.rx.common.App;
import org.rx.common.NQuery;
import org.rx.common.UserConfig;
import org.rx.fl.dto.bot.BotType;
import org.rx.fl.dto.bot.MessageInfo;
import org.rx.fl.dto.bot.OpenIdInfo;
import org.rx.fl.dto.repo.UserInfo;
import org.rx.fl.repository.model.Commission;
import org.rx.fl.repository.model.Order;
import org.rx.fl.service.bot.WxMobileBot;
import org.rx.fl.service.command.Command;
import org.rx.fl.service.command.impl.AliPayCmd;
import org.rx.fl.service.order.NotifyOrdersInfo;
import org.rx.fl.service.user.UserService;
import org.springframework.beans.factory.annotation.Autowired;
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
    private UserService userService;
    @Resource
    private AliPayCmd aliPayCmd;
    private final ConcurrentLinkedQueue<List<MessageInfo>> queue;

    @Autowired
    public NotifyService(UserService userService, UserConfig userConfig) {
        queue = new ConcurrentLinkedQueue<>();
        TaskFactory.schedule(this::push, 2 * 1000);

        this.userService = userService;
        $<OpenIdInfo> openId = $.$();
        String adminId = NQuery.of(userConfig.getAdminIds()).firstOrDefault();
        if (adminId != null) {
            openId.$ = userService.getOpenId(adminId, BotType.Wx);
            TaskFactory.schedule(() -> App.catchCall(() -> {
                MessageInfo heartbeat = new MessageInfo(openId.$);
                heartbeat.setContent(String.format("Heartbeat %s", DateTime.now().toDateTimeString()));
                botService.pushMessages(heartbeat);
            }), userConfig.getHeartbeatMinutes() * 60 * 1000);
        }

        if (userConfig.getAliPayCode() != null && openId.$ != null) {
            TaskFactory.setTimeout(() -> {
                MessageInfo msg = new MessageInfo(openId.$);
                msg.setContent(userConfig.getAliPayCode());
                botService.handleMessage(msg);
            }, 10 * 1000);
        }

        TaskFactory.schedule(() -> App.catchCall(() -> {
            if (Strings.isNullOrEmpty(aliPayCmd.getSourceMessage())) {
                return;
            }
            int hours = DateTime.now().getHours();
            switch (hours) {
                case 8:
                case 11:
                case 12:
                case 18:
                    for (String whiteOpenId : WxMobileBot.whiteOpenIds) {
                        MessageInfo message = new MessageInfo();
                        message.setBotType(BotType.Wx);
                        message.setOpenId(whiteOpenId);
                        message.setContent(aliPayCmd.getSourceMessage());
                        botService.pushMessages(message);
                    }
                    break;
            }
        }), 58 * 60 * 1000);
    }

    @SneakyThrows
    private void push() {
        List<MessageInfo> messages;
        while ((messages = queue.poll()) != null) {
            botService.pushMessages(messages);
        }
        Thread.sleep(200);
    }

    public void add(String userId, String content) {
        require(userId, content);

        add(userId, Collections.singletonList(content));
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
                            "%s" +
                            "亲 确认收货成功后，回复 提现 两个字，给您补贴红包",
                    paidOrder.getGoodsName(), paidOrder.getOrderNo(),
                    toMoney(paidOrder.getPayAmount()), toMoney(paidOrder.getRebateAmount()),
                    toMoney(user.getBalance()), toMoney(user.getUnconfirmedOrderAmount()), Command.splitText),
                    userService.getRelationMessage(user.getUserId())));
        }
        for (Order settleOrder : notifyInfo.settleOrders) {
            UserInfo user = userService.queryUser(settleOrder.getUserId());
            add(user.getUserId(), String.format("一一一一收 货 成 功一一一一\n" +
                            "%s\n" +
                            "订单编号:\n" +
                            "%s\n" +
                            "付费金额: %.2f元\n" +
                            "红包补贴: %.2f元\n" +
                            "\n" +
                            "可提现金额: %.2f元\n" +
                            "未收货金额: %.2f元\n" +
                            "总成功订单: %s单\n" +
                            "%s" +
                            "回复 提现 两个字，给您补贴红包\n" +
                            "补贴红包已转入可提现金额",
                    settleOrder.getGoodsName(), settleOrder.getOrderNo(),
                    toMoney(settleOrder.getPayAmount()), toMoney(settleOrder.getSettleAmount()),
                    toMoney(user.getBalance()), toMoney(user.getUnconfirmedOrderAmount()), user.getConfirmedOrderCount(), Command.splitText));
        }
        for (Order order : notifyInfo.restoreSettleOrder) {
            UserInfo user = userService.queryUser(order.getUserId());
            add(user.getUserId(), String.format("一一一一退 货 成 功一一一一\n" +
                            "%s\n" +
                            "订单编号:\n" +
                            "%s\n" +
                            "付费金额: %.2f元\n" +
                            "红包补贴: %.2f元\n" +
                            "\n" +
                            "可提现金额: %.2f元\n" +
                            "未收货金额: %.2f元\n" +
                            "总成功订单: %s单\n" +
                            "%s" +
                            "补贴红包已从可提现金额扣除", order.getGoodsName(), order.getOrderNo(),
                    toMoney(order.getPayAmount()), toMoney(order.getSettleAmount()),
                    toMoney(user.getBalance()), toMoney(user.getUnconfirmedOrderAmount()), user.getConfirmedOrderCount(), Command.splitText));
        }
        for (Commission commission : notifyInfo.paidCommissionOrders) {
            add(commission.getUserId(), String.format("一一一一伙 伴 支 付一一一一\n" +
                    "亲，您的伙伴已下单，伙伴确认收货后可收到红包补贴约%.2f元", toMoney(commission.getAmount())));
        }
        for (Commission commission : notifyInfo.settleCommissionOrders) {
            add(commission.getUserId(), String.format("一一一一伙 伴 收 货一一一一\n" +
                    "亲，您的伙伴已收货，红包补贴%.2f元已转入可提现金额", toMoney(commission.getAmount())));
        }
    }
}
