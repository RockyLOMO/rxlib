package org.rx.fl.service.bot;

import com.google.common.base.Strings;
import lombok.extern.slf4j.Slf4j;
import org.rx.beans.DateTime;
import org.rx.common.InvalidOperationException;
import org.rx.common.NQuery;
import org.rx.fl.dto.bot.BotType;
import org.rx.fl.dto.bot.MessageInfo;
import org.rx.fl.util.AwtBot;
import org.rx.fl.util.ImageUtil;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

import static org.rx.common.Contract.isNull;
import static org.rx.common.Contract.require;
import static org.rx.util.AsyncTask.TaskFactory;

@Slf4j
public class WxMobileBot implements Bot {
    public interface KeyImages {
        BufferedImage Key = ImageUtil.getImageFromResource(WxMobileBot.class, "/static/wxKey.png");
        BufferedImage Unread0 = ImageUtil.getImageFromResource(WxMobileBot.class, "/static/wxUnread0.png");
        BufferedImage Unread1 = ImageUtil.getImageFromResource(WxMobileBot.class, "/static/wxUnread1.png");
        BufferedImage Msg = ImageUtil.getImageFromResource(WxMobileBot.class, "/static/wxMsg.png");
        BufferedImage Browser = ImageUtil.getImageFromResource(WxMobileBot.class, "/static/wxBrowser.png");
//        BufferedImage Group = ImageUtil.getImageFromResource(WxMobileBot.class, "/static/wxGroup.png");
    }

    private static final NQuery<String> skipOpenIds = NQuery.of("weixin", "filehelper");

    private AwtBot bot;
    private DateTime lastTime;
    private Point windowPoint;
    private Function<MessageInfo, String> event;
    private int capturePeriod, maxCheckMessageCount, maxCaptureMessageCount, maxScrollMessageCount;
    private final ReentrantLock locker;
    private volatile boolean clickDefaultUser;
    private volatile Future future;

    @Override
    public BotType getType() {
        return BotType.Wx;
    }

    private Point getWindowPoint() {
        if (DateTime.now().subtract(lastTime).getTotalMinutes() > 1) {
            windowPoint = null;
        }
        if (windowPoint == null) {
            Point point = bot.findScreenPoint(KeyImages.Key);
            if (point == null) {
                bot.saveScreen(KeyImages.Key, "WxMobile");
                throw new InvalidOperationException("WxMobile window not found");
            }

            int x = point.x - 19, y = point.y - 26;
            windowPoint = new Point(x, y);
        }
        return windowPoint;
    }

    public WxMobileBot(int capturePeriod, int maxCheckMessageCount, int maxCaptureMessageCount, int maxScrollMessageCount) {
        bot = new AwtBot();
        lastTime = DateTime.now();
        getWindowPoint();

        this.capturePeriod = capturePeriod;
        this.maxCheckMessageCount = maxCheckMessageCount;
        this.maxCaptureMessageCount = maxCaptureMessageCount;
        this.maxScrollMessageCount = maxScrollMessageCount;
        locker = new ReentrantLock(true);
        clickDefaultUser = true;
    }

    public void start() {
        if (future != null) {
            return;
        }

        future = TaskFactory.schedule(() -> {
            try {
                //抛异常会卡住
                captureUsers();
            } catch (Exception e) {
                log.warn(e.getMessage(), e);
            }
        }, capturePeriod);
    }

    public void stop() {
        if (future == null) {
            return;
        }

        future.cancel(false);
        future = null;
    }

    private void captureUsers() {
        locker.lock();
        try {
            Point point = getAbsolutePoint(61, 63);
            Rectangle rectangle = new Rectangle(point, new Dimension(250, 438));
//            log.info("captureUsers at {}", rectangle);
            int checkCount = 0;
            do {
                for (BufferedImage partImg : new BufferedImage[]{KeyImages.Unread0, KeyImages.Unread1}) {
                    Point screenPoint;
                    while ((screenPoint = bot.findScreenPoint(partImg, rectangle)) != null) {
                        clickDefaultUser = true;
                        checkCount = 0;
                        log.info("step1 captureUser at {}", screenPoint);
                        bot.mouseLeftClick(screenPoint.x, screenPoint.y + 20);
                        bot.delay(50);

                        Point msgPoint = getAbsolutePoint(311, 63);
                        bot.mouseMove(msgPoint.x + 20, msgPoint.y + 20);

                        MessageInfo messageInfo = new MessageInfo();
                        messageInfo.setBotType(this.getType());
                        Set<String> msgList = new LinkedHashSet<>();
                        int scrollMessageCount = 0;
                        Rectangle msgRectangle = new Rectangle(msgPoint, new Dimension(400, 294));
                        boolean doLoop = true;
                        do {
                            List<Point> points = bot.findScreenPoints(KeyImages.Msg, msgRectangle);
                            log.info("step2 captureMessages {}", points.size());
                            for (int i = points.size() - 1; i >= 0; i--) {
                                Point p = points.get(i);
                                if (messageInfo.getOpenId() == null) {
                                    bot.setClipboardString("");

                                    int x = p.x - 22, y = p.y + 12;
                                    bot.mouseLeftClick(x, y);
                                    bot.delay(100);

                                    fillOpenId(messageInfo, new Point(x, y), false);
                                    if (Strings.isNullOrEmpty(messageInfo.getOpenId())) {
                                        doLoop = false;
                                        break;
                                    }

                                    bot.mouseLeftClick(msgPoint.x + 10, msgPoint.y + 10);
                                    bot.delay(100);
                                }
                                int x = p.x + KeyImages.Msg.getWidth() + 8, y = p.y + KeyImages.Msg.getHeight() / 2;
                                bot.mouseDoubleLeftClick(x, y);

                                bot.delay(100);
                                Point pBrowser = getAbsolutePoint(348, 402);
                                Point pCopy = bot.clickByImage(KeyImages.Browser, new Rectangle(pBrowser, new Dimension(355, 95)), false);
                                log.info("step2-2 capture url {}", pCopy);
                                if (pCopy != null) {
                                    bot.mouseLeftClick(pCopy.x + KeyImages.Browser.getWidth() / 2, pCopy.y + KeyImages.Browser.getHeight() / 2);
                                    bot.delay(AwtBot.clipboardDelay);
                                }
                                String msg = bot.keyCopyString();
                                log.info("step2-2 capture msg {}", msg);
                                msgList.add(msg);
                                if (msgList.size() >= maxCaptureMessageCount) {
                                    break;
                                }
                            }

                            if (doLoop && msgList.size() < maxCaptureMessageCount) {
                                bot.mouseWheel(-5);
                                bot.delay(1000);
                                scrollMessageCount++;
                            }
                        }
                        while (doLoop && scrollMessageCount <= maxScrollMessageCount && msgList.size() < maxCaptureMessageCount);
                        if (!doLoop) {
                            continue;
                        }
                        if (msgList.isEmpty()) {
                            if (Strings.isNullOrEmpty(messageInfo.getOpenId())) {
                                bot.mouseLeftClick(getAbsolutePoint(350, 38));
                                bot.delay(500); //多100
                                Point p = getAbsolutePoint(808, 52);
                                bot.mouseLeftClick(p);
                                fillOpenId(messageInfo, p, true);
                            }
                            messageInfo.setContent(Bot.SubscribeContent);
                        } else {
                            messageInfo.setContent(NQuery.of(msgList).first());
                        }
                        if (event != null) {
                            TaskFactory.run(() -> {
                                String toMsg = event.apply(messageInfo);
                                if (Strings.isNullOrEmpty(toMsg)) {
                                    return;
                                }
                                messageInfo.setContent(toMsg);
                                sendMessage(messageInfo);
                            });
                        }
                    }
                }
                checkCount++;
            } while (checkCount < maxCheckMessageCount);

            if (clickDefaultUser) {
                bot.mouseLeftClick(getAbsolutePoint(94, 415));
                bot.delay(50);
                clickDefaultUser = false;
            }
        } finally {
            locker.unlock();
        }
    }

    private void fillOpenId(MessageInfo message, Point point, boolean captureNickname) {
        bot.mouseDoubleLeftClick(point.x + 94, point.y + 72);
        String openId = bot.keyCopyString();
        log.info("step2-1 capture openId {}", openId);
        if (Strings.isNullOrEmpty(openId) || skipOpenIds.contains(openId)) {
            log.warn("Can not found openId {}", openId);
            return;
        }
        message.setOpenId(openId);
        if (message.getOpenId().startsWith("wxid_") || captureNickname) {
            bot.mouseDoubleLeftClick(point.x + 42, point.y + 45);
            String nickname = bot.keyCopyString();
            log.info("step2-1 capture nickname {}", nickname);
            if (Strings.isNullOrEmpty(nickname)) {
                log.warn("Can not found nickname");
                return;
            }
            message.setNickname(nickname);
            bot.mouseLeftClick(point.x + 106, point.y + 146);
        }
    }

    private Point getAbsolutePoint(int relativeX, int relativeY) {
        Point windowPoint = getWindowPoint();
        return new Point(windowPoint.x + relativeX, windowPoint.y + relativeY);
    }

    @Override
    public void onReceiveMessage(Function<MessageInfo, String> event) {
        this.event = event;
    }

    @Override
    public void sendMessage(MessageInfo message) {
        require(message);
        if (skipOpenIds.contains(message.getOpenId())) {
            return;
        }

        locker.lock();
        try {
//            Point point = getAbsolutePoint(110, 38);
//            bot.mouseLeftClick(point);
//            bot.delay(50);
//            log.info("step1 click input ok");
//            bot.keyPressFind();
            bot.mouseLeftClick(getAbsolutePoint(32, 92));
            bot.delay(50);
            bot.mouseLeftClick(getAbsolutePoint(110, 38));
            bot.delay(100);
            log.info("step1 focus input ok");

            String openId = isNull(message.getNickname(), message.getOpenId()), msg = message.getContent();
            bot.keyParseString(openId);
//            bot.keyPressSpace();
            bot.delay(1050); //多100
            log.info("step1-1 input openId {}", openId);

            bot.mouseLeftClick(getAbsolutePoint(166, 132));
            bot.delay(100);
            log.info("step1-2 click user {}", openId);

            bot.keyParseString(msg);
            bot.keyPressEnter();
            bot.delay(200);
            log.info("step2 send msg {} to user {}", msg, openId);

            bot.mouseLeftClick(getAbsolutePoint(30, 92));
            bot.delay(50);
            bot.mouseLeftClick(getAbsolutePoint(94, 478));
            bot.delay(50);
        } finally {
            locker.unlock();
        }
    }
}
