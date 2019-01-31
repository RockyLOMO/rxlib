package org.rx.test;

import lombok.SneakyThrows;
import org.junit.Test;
import org.rx.common.App;
import org.rx.common.Contract;
import org.rx.common.SystemException;
import org.rx.fl.util.AwtBot;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Map;

public class BotTests {
    @SneakyThrows
    @Test
    public void getScreenPoint() {
        AwtBot bot = new AwtBot();
        Point point = bot.getScreenPoint("D:\\u1.png");
        System.out.println(point);
        int offsetX = point.x - 15, offsetY = point.y - 22;
        BufferedImage image = bot.capturePartScreen(offsetX, offsetY, 710, 500);
        ImageIO.write(image, "png", new File("D:\\u2.png"));
        bot.mouseLeftClick(offsetX + 82, offsetY + 82);
        bot.delay(1000);
//        bot.mouseDoubleLeftClick(offsetX + 400, offsetY + 342);
        int x = offsetX + 400, y = offsetY + 342;
//        bot.keyCopy();
        for (int i = 0; i < 20; i++) {
            bot.mouseRightClick(x, y);
            bot.mouseLeftClick(x + 38, y + 16);
            System.out.println("ClipboardString: " + AwtBot.getClipboardString());
            bot.mouseWheel(-1);
        }
//        for (int i = 0; i < 5; i++) {
//            bot.keysPress(KeyEvent.VK_DOWN);
//            bot.delay(1000);
    }
}
