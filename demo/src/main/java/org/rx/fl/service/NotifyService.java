package org.rx.fl.service;

import lombok.SneakyThrows;
import org.rx.common.App;
import org.rx.common.NQuery;
import org.rx.fl.dto.bot.MessageInfo;
import org.rx.fl.dto.bot.OpenIdInfo;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.rx.common.Contract.require;
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
}
