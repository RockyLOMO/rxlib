package org.rx.fl.util;

import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import org.rx.common.App;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import static org.rx.common.Contract.require;

public class AwtBot {
    @SneakyThrows
    public static String getClipboardString() {
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        Transferable t = clipboard.getContents(null);
        if (null != t && t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
            return (String) t.getTransferData(DataFlavor.stringFlavor);
        }
        return "";
    }

    public static boolean partEquals(BufferedImage image, int imageX, int imageY, BufferedImage partImage) {
        require(image, partImage);

        int partW = partImage.getWidth();
        int partH = partImage.getHeight();
        for (int w = 0; w < partW; w++) {
            for (int h = 0; h < partH; h++) {
                if (image.getRGB(imageX + w, imageY + h) != partImage.getRGB(w, h)) {
                    return false;
                }
            }
        }
        return true;
    }

    private final Robot bot;
    @Getter
    @Setter
    private volatile int autoDelay;

    @SneakyThrows
    public AwtBot() {
        bot = new Robot();
        autoDelay = App.readSetting("app.awtBot.delay");
    }

    public void delay(int delay) {
        bot.delay(delay);
    }

    @SneakyThrows
    public Point getScreenPoint(String imageFile) {
        require(imageFile);

        return getScreenPoint(ImageIO.read(new File(imageFile)));
    }

    public Point getScreenPoint(BufferedImage partImage) {
        require(partImage);

        BufferedImage screenImage = captureFullScreen();
        int width = screenImage.getWidth(), height = screenImage.getHeight();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                if (partEquals(screenImage, x, y, partImage)) {
                    return new Point(x, y);
                }
            }
        }
        return null;
    }

    public void mouseLeftClick(int x, int y) {
        mouseLeftClickInner(x, y);
        bot.delay(autoDelay);
    }

    private void mouseLeftClickInner(int x, int y) {
        bot.mouseMove(x, y);
        bot.mousePress(InputEvent.BUTTON1_MASK);
        bot.mouseRelease(InputEvent.BUTTON1_MASK);
    }

    public void mouseDoubleLeftClick(int x, int y) {
        mouseLeftClickInner(x, y);
        mouseLeftClick(x, y);
    }

    public void mouseRightClick(int x, int y) {
        bot.mouseMove(x, y);
        bot.mousePress(InputEvent.BUTTON3_MASK);
        bot.mouseRelease(InputEvent.BUTTON3_MASK);
        bot.delay(autoDelay);
    }

    public void mouseWheel(int wheelAmt) {
        bot.mouseWheel(wheelAmt);
        bot.delay(autoDelay);
    }

    public void keysPress(int... keys) {
        require(keys);

        for (int i = 0; i < keys.length; i++) {
            bot.keyPress(keys[i]);
            bot.keyRelease(keys[i]);
            bot.delay(autoDelay);
        }
    }

    public void keyCopy() {
        bot.keyPress(KeyEvent.VK_CONTROL);
        bot.keyPress(KeyEvent.VK_C);
        bot.keyRelease(KeyEvent.VK_C);
        bot.keyRelease(KeyEvent.VK_CONTROL);
        bot.delay(autoDelay);
    }

    public void keyParse() {
        bot.keyPress(KeyEvent.VK_CONTROL);
        bot.keyPress(KeyEvent.VK_V);
        bot.keyRelease(KeyEvent.VK_V);
        bot.keyRelease(KeyEvent.VK_CONTROL);
        bot.delay(autoDelay);
    }

    public BufferedImage captureFullScreen() {
        return bot.createScreenCapture(new Rectangle(Toolkit.getDefaultToolkit().getScreenSize()));
    }

    public BufferedImage capturePartScreen(int x, int y, int width, int height) {
        return bot.createScreenCapture(new Rectangle(x, y, width, height));
    }
}
