package org.rx.fl.util;

import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.beans.DateTime;
import org.rx.common.App;
import org.rx.common.InvalidOperationException;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

import static org.rx.common.Contract.isNull;
import static org.rx.common.Contract.require;
import static org.rx.util.AsyncTask.TaskFactory;

@Slf4j
public class AwtBot {
    private static final String logPath = App.readSetting("app.awtBot.logPath");

    public static void init() {
        System.setProperty("java.awt.headless", "false");
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        log.info("ScreenSize: {}", screenSize);
    }

    @SneakyThrows
    public static String getClipboardString() {
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        Transferable t = clipboard.getContents(null);
        if (null != t && t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
            return (String) t.getTransferData(DataFlavor.stringFlavor);
        }
        return "";
    }

    private final Robot bot;
    @Getter
    @Setter
    private volatile int autoDelay;
    private volatile Rectangle screenRectangle;
    @Getter
    @Setter
    private volatile boolean debug;

    public Rectangle getScreenRectangle() {
        return isNull(screenRectangle, new Rectangle(Toolkit.getDefaultToolkit().getScreenSize()));
    }

    public void setScreenRectangle(Rectangle screenRectangle) {
        require(screenRectangle);

        this.screenRectangle = screenRectangle;
    }

    @SneakyThrows
    public AwtBot() {
        bot = new Robot();
        autoDelay = App.readSetting("app.awtBot.delay");
    }

    public void delay(int delay) {
        bot.delay(delay);
    }

    public void clickByImage(BufferedImage keyImg) {
        clickByImage(keyImg, getScreenRectangle());
    }

    @SneakyThrows
    public void clickByImage(BufferedImage keyImg, Rectangle screenRectangle) {
        require(keyImg);

        Point point = findScreenPoint(keyImg, screenRectangle);
        if (point == null) {
            debug(keyImg, "clickByImage");
            throw new InvalidOperationException("Can not found point");
        }

        int x = point.x + keyImg.getWidth() / 2, y = point.y + keyImg.getHeight() / 2;
        mouseLeftClick(x, y);
    }

    private void debug(BufferedImage img, String msg) {
        if (!debug) {
            return;
        }

        TaskFactory.run(() -> {
            String fileName = DateTime.now().toString(DateTime.Formats.last());
            ImageUtil.saveImage(captureScreen(), String.format("%s/%s_0_%s.%s", logPath, fileName, msg, ImageUtil.ImageFormat));
            if (img == null) {
                return;
            }
            ImageUtil.saveImage(img, String.format("%s/%s_1_%s.%s", logPath, fileName, msg, ImageUtil.ImageFormat));
        });
    }

    @SneakyThrows
    public Point findScreenPoint(String partImageFile) {
        require(partImageFile);

        return findScreenPoint(ImageIO.read(new File(partImageFile)));
    }

    public Point findScreenPoint(BufferedImage partImage) {
        return findScreenPoint(partImage, getScreenRectangle());
    }

    public Point findScreenPoint(BufferedImage partImage, Rectangle screenRectangle) {
        require(partImage, screenRectangle);

        return ImageUtil.findPoint(captureScreen(screenRectangle), partImage, false);
    }

    public List<Point> findScreenPoints(BufferedImage partImage) {
        return findScreenPoints(partImage, getScreenRectangle());
    }

    public List<Point> findScreenPoints(BufferedImage partImage, Rectangle screenRectangle) {
        require(partImage, screenRectangle);

        return ImageUtil.findPoints(captureScreen(screenRectangle), partImage);
    }

    public BufferedImage captureScreen() {
        return captureScreen(getScreenRectangle());
    }

    public BufferedImage captureScreen(int x, int y, int width, int height) {
        return captureScreen(new Rectangle(x, y, width, height));
    }

    public BufferedImage captureScreen(Rectangle rectangle) {
        require(rectangle);

        return bot.createScreenCapture(rectangle);
    }

    public void mouseMove(int x, int y) {
        bot.mouseMove(x, y);
        bot.delay(autoDelay);
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
}
