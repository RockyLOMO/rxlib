package org.rx.fl.service;

import lombok.SneakyThrows;
import org.rx.beans.DateTime;
import org.rx.common.App;
import org.rx.common.NQuery;
import org.rx.common.UserConfig;
import org.rx.fl.dto.bot.BotType;
import org.rx.fl.dto.bot.MessageInfo;
import org.rx.fl.dto.bot.OpenIdInfo;
import org.rx.fl.service.user.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.rx.common.Contract.require;
import static org.rx.util.AsyncTask.TaskFactory;

@Service
public class NotifyService {
    @Resource
    private BotService botService;
    private UserService userService;
    private final ConcurrentLinkedQueue<List<MessageInfo>> queue;

    @Autowired
    public NotifyService(UserService userService, UserConfig userConfig) {
        queue = new ConcurrentLinkedQueue<>();
        TaskFactory.schedule(() -> App.catchCall(this::push), 2 * 1000);

        this.userService = userService;
        String adminId = NQuery.of(userConfig.getAdminIds()).firstOrDefault();
        if (adminId != null) {
            TaskFactory.schedule(() ->
                    add(adminId, String.format("H %s", DateTime.now().toDateTimeString())), userConfig.getHeartbeatMinutes() * 60 * 1000);

            OpenIdInfo openId = userService.getOpenId(adminId, BotType.Wx);
            if (userConfig.getAliPayCode() != null) {
                TaskFactory.scheduleOnce(() -> {
                    MessageInfo msg = new MessageInfo(openId);
                    msg.setContent(userConfig.getAliPayCode());
                    botService.handleMessage(msg);
                }, 10 * 1000);
            }
        }
    }

    @SneakyThrows
    private void push() {
        List<MessageInfo> messages;
        while ((messages = queue.poll()) != null) {
            botService.pushMessages(messages);
            Thread.sleep(200);
        }
    }

    public void add(String userId, String content) {
        require(userId, content);

        add(userId, Collections.singletonList(content));
    }

    public void add(String userId, List<String> contents) {
        require(userId, contents);

        List<OpenIdInfo> openIds = App.getOrStore(String.format("NotifyService.getOpenIds(%s)", userId), k -> userService.getOpenIds(userId), App.CacheContainerKind.ObjectCache);
        List<MessageInfo> messages = NQuery.of(openIds).selectMany(p -> NQuery.of(contents).select(content -> {
            MessageInfo msg = new MessageInfo(p);
//            msg.setBotType(p.getBotType());
//            msg.setOpenId(p.getOpenId());
//            msg.setNickname(p.getNickname());
            msg.setContent(content);
            return msg;
        }).toList()).toList();
        queue.add(messages);
    }

    public void addGroup(String groupId, String content) {
        require(groupId, content);

        MessageInfo message = new MessageInfo();
        message.setBotType(BotType.Wx);
        message.setOpenId(groupId);
        message.setContent(content);
        queue.add(Collections.singletonList(message));
    }
}
