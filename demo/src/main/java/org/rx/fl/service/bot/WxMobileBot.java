package org.rx.fl.service.bot;

import org.rx.common.InvalidOperationException;
import org.rx.fl.util.AwtBot;

import java.awt.*;
import java.awt.image.BufferedImage;

public class WxMobileBot {
    private interface KeyImages {
        BufferedImage Key = AwtBot.getImageFromResource(WxMobileBot.class, "/static/wxKey.png");
        BufferedImage Unread0 = AwtBot.getImageFromResource(WxMobileBot.class, "/static/wxUnread0.png");
        BufferedImage Unread1 = AwtBot.getImageFromResource(WxMobileBot.class, "/static/wxUnread1.png");
        BufferedImage Msg = AwtBot.getImageFromResource(WxMobileBot.class, "/static/wxMsg.png");
    }

    private AwtBot bot;

    private Point getWindowPoint() {
        Point point = bot.findScreenPoint(KeyImages.Key);
        if (point == null) {
            throw new InvalidOperationException("WxMobile window not found");
        }

        int x = point.x - 15, y = point.y - 22;
        return new Point(x, y);
    }

    private BufferedImage getWindowImage() {
        Point point = getWindowPoint();
        return bot.captureScreen(point.x, point.y, 710, 500);
    }

    public WxMobileBot() {
        bot = new AwtBot();
    }
}
