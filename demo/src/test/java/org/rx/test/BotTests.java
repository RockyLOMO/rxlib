package org.rx.test;

import com.alibaba.fastjson.JSON;
import lombok.SneakyThrows;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.rx.common.App;
import org.rx.common.Contract;
import org.rx.common.NQuery;
import org.rx.common.SystemException;
import org.rx.fl.service.bot.WxMobileBot;
import org.rx.fl.service.media.JdLogin;
import org.rx.fl.util.AwtBot;
import org.rx.fl.util.ImageUtil;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import java.util.List;

//@RunWith(SpringRunner.class)
//@SpringBootTest
public class BotTests {
    @SneakyThrows
    @Test
    public void wx() {
        WxMobileBot bot = new WxMobileBot(500, 2, 1, 1);
        bot.onReceiveMessage(p -> {
            System.out.println(JSON.toJSONString(p));
            return "已收到消息：" + p.getContent();
//            return "";
        });
        System.out.println("start...");
        System.in.read();
    }

    @SneakyThrows
    @Test
    public void jdHack() {
        JdLogin login = new JdLogin(8081);
        Thread.sleep(2000);
        System.out.println(login.produceKey());
        System.in.read();
    }

    @SneakyThrows
    @Test
    public void getScreenPoint() {
//        AwtBot bot = new AwtBot();
//        List<Point> points = bot.findScreenPoints(ImageUtil.loadImage("D:\\4.png"));
//        System.out.println(points);
//        points = bot.findScreenPoints(ImageUtil.loadImage("D:\\1.png"));
//        System.out.println(points);

        Class owner = AwtBot.class;
        AwtBot bot = new AwtBot();
        List<Point> points = bot.findScreenPoints(ImageUtil.getImageFromResource(owner, "/static/wxUnread0.png"));
        System.out.println(points);
        points = bot.findScreenPoints(ImageUtil.getImageFromResource(owner, "/static/wxUnread1.png"));
//        points = bot.findScreenPoints(ImageUtil.loadImage("D:\\1.png"));
        System.out.println(points);


//        AwtBot bot = new AwtBot();
//        Point point = bot.findScreenPoint(AwtBot.getImageFromResource(owner, "/static/wxKey.png"));
//        System.out.println(point);
//
//        int offsetX = point.x - 15, offsetY = point.y - 22;
//        BufferedImage chatImg = bot.captureScreen(offsetX, offsetY, 710, 500);
//        ImageIO.write(chatImg, "png", new File("D:\\d1.png"));
//
//
//
//
//
//
//
//        bot.mouseLeftClick(offsetX + 94, offsetY + 94);
//        bot.delay(1000);
//
//        int msgOffsetX = offsetX + 311, msgOffsetY = offsetY + 63;
//        bot.mouseMove(msgOffsetX + 20, msgOffsetY + 20);
//
//        BufferedImage wxMsgKey = AwtBot.getImageFromResource(owner, "/static/wxMsg.png");
//        Set<String> msgList = new LinkedHashSet<>();
//        int emptyPointCount = 0, maxEmptyPointCount = 3;
//        do {
//            BufferedImage msgImg = bot.captureScreen(msgOffsetX, msgOffsetY, 400, 294);
////            ImageIO.write(msgImg, "png", new File("D:\\d2.png"));
//            List<Point> points = AwtBot.findPoints(msgImg, wxMsgKey);
//            if (points.isEmpty()) {
//                bot.mouseWheel(-5);
//                bot.delay(1000);
//
//                emptyPointCount++;
//                continue;
//            }
//            System.out.println(points);
//
//            for (int i = points.size() - 1; i >= 0; i--) {
//                Point p = points.get(i);
//                int x = msgOffsetX + p.x + wxMsgKey.getWidth() / 2, y = msgOffsetY + p.y + wxMsgKey.getHeight() / 2;
//                bot.mouseRightClick(x, y);
//                bot.delay(200);
//                bot.mouseLeftClick(x + 38, y + 16);
//                bot.delay(50);
//                String msg = AwtBot.getClipboardString();
//                msgList.add(msg);
//            }
//
//            bot.mouseWheel(-5);
//            bot.delay(1000);
//        }
//        while (emptyPointCount < maxEmptyPointCount && msgList.size() < 20);
//        System.out.println(msgList);
//
////        int x = offsetX + 370, y = offsetY + 355;
////        for (int i = 0; i < 20; i++) {
//////            bot.mouseRightClick(x, y);
//////            bot.delay(50);
//////            bot.mouseLeftClick(x + 38, y + 16);
////////            bot.delay(50);
//////            String msg = AwtBot.getClipboardString();
//////            set.add(msg);
//////            System.out.println("ClipboardString: " + msg);
//////            bot.mouseWheel(-1);
//////            bot.delay(50);
////        }
////        System.out.println(set);
//
////        for (int i = 0; i < 10; i++) {
////            bot.keysPress(KeyEvent.VK_DOWN);
////            bot.delay(1000);
////        }
//
//
//////        bot.mouseDoubleLeftClick(offsetX + 400, offsetY + 342);
////        int x = offsetX + 400, y = offsetY + 342;
//////        bot.keyCopy();
////        for (int i = 0; i < 20; i++) {
////            bot.mouseRightClick(x, y);
////            bot.mouseLeftClick(x + 38, y + 16);
////            System.out.println("ClipboardString: " + AwtBot.getClipboardString());
////            bot.mouseWheel(-1);
////        }
//////        for (int i = 0; i < 5; i++) {
//////            bot.keysPress(KeyEvent.VK_DOWN);
//////            bot.delay(1000);
    }
}
