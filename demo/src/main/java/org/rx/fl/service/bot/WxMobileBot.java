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
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

import static org.rx.common.Contract.require;
import static org.rx.util.AsyncTask.TaskFactory;

@Slf4j
public class WxMobileBot implements Bot {
    public interface KeyImages {
        BufferedImage Key = ImageUtil.getImageFromResource(WxMobileBot.class, "/static/wxKey.png");
        BufferedImage Unread0 = ImageUtil.getImageFromResource(WxMobileBot.class, "/static/wxUnread0.png");
        BufferedImage Unread1 = ImageUtil.getImageFromResource(WxMobileBot.class, "/static/wxUnread1.png");
        BufferedImage Msg = ImageUtil.getImageFromResource(WxMobileBot.class, "/static/wxMsg.png");
    }

    private static final NQuery<String> skipOpenIds = NQuery.of("weixin");

    private AwtBot bot;
    private Point windowPoint;
    private DateTime lastTime;
    private int maxCheckMessageCount, maxCaptureMessageCount, maxScrollMessageCount;
    private Function<MessageInfo, String> event;
    private volatile boolean clickDefaultUser;
    private final ReentrantLock locker;

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
                throw new InvalidOperationException("WxMobile window not found");
            }

            int x = point.x - 19, y = point.y - 26;
            windowPoint = new Point(x, y);
        }
        return windowPoint;
    }

    public WxMobileBot(int capturePeriod, int maxCheckMessageCount, int maxCaptureMessageCount, int maxScrollMessageCount) {
        locker = new ReentrantLock(true);
        bot = new AwtBot();
        this.maxCheckMessageCount = maxCheckMessageCount;
        this.maxCaptureMessageCount = maxCaptureMessageCount;
        this.maxScrollMessageCount = maxScrollMessageCount;
        clickDefaultUser = true;
        lastTime = DateTime.now();
        TaskFactory.schedule(() -> {
            try {
                //抛异常会卡住
                captureUsers();
            } catch (Exception e) {
                log.warn(e.getMessage());
            }
        }, capturePeriod);
    }

    private BufferedImage getWindowImage() {
        Point point = getWindowPoint();
        return bot.captureScreen(point.x, point.y, 710, 500);
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
                                    int x = p.x - 22, y = p.y + 12;
                                    bot.mouseLeftClick(x, y);
                                    bot.delay(50);

                                    bot.mouseDoubleLeftClick(x + 94, y + 72);
                                    String openId = bot.keyCopyString();
                                    log.info("step2-1 capture openId {}", openId);
                                    if (Strings.isNullOrEmpty(openId)) {
                                        throw new InvalidOperationException("Can not found openId");
                                    }
                                    if (skipOpenIds.contains(openId)) {
                                        log.info("skip openId {}", openId);
                                        doLoop = false;
                                        break;
                                    }
                                    messageInfo.setOpenId(openId);
                                    bot.mouseLeftClick(msgPoint.x + 10, msgPoint.y + 10);
                                    bot.delay(50);
                                }
                                int x = p.x + KeyImages.Msg.getWidth() + 8, y = p.y + KeyImages.Msg.getHeight() / 2;
                                bot.mouseDoubleLeftClick(x, y);
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
                            messageInfo.setSubscribe(true);
                        } else {
                            messageInfo.setContent(NQuery.of(msgList).firstOrDefault());
                        }
                        if (event != null) {
                            TaskFactory.run(() -> {
                                String toMsg = event.apply(messageInfo);
                                if (!Strings.isNullOrEmpty(toMsg)) {
                                    sendMessage(messageInfo.getOpenId(), toMsg);
                                }
                            });
                        }
                    }
                }
                checkCount++;
            } while (checkCount < maxCheckMessageCount);

            if (clickDefaultUser) {
                bot.mouseLeftClick(getAbsolutePoint(94, 478));
                bot.delay(50);
                clickDefaultUser = false;
            }
        } finally {
            locker.unlock();
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
    public void sendMessage(String openId, String msg) {
        require(openId, msg);
        if (skipOpenIds.contains(openId)) {
            return;
        }

        locker.lock();
        try {
            Point point = getAbsolutePoint(108, 38);
            bot.mouseLeftClick(point);
            //双击2次确认
            bot.delay(50);
//            bot.mouseLeftClick(point);
//            bot.delay(50);
            log.info("step1 click input ok");

            bot.keyParseString(openId);
//            bot.keyPressSpace();
            bot.delay(800);
            log.info("step1-1 input openId {}", openId);

            bot.mouseLeftClick(getAbsolutePoint(166, 132));
            bot.delay(50);
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
