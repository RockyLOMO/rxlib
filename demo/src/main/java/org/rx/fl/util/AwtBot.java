package org.rx.fl.util;

import com.google.common.base.Strings;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.beans.DateTime;
import org.rx.common.App;
import org.rx.common.InvalidOperationException;
import org.rx.common.Lazy;
import org.rx.common.NQuery;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

import static org.rx.common.Contract.*;
import static org.rx.util.AsyncTask.TaskFactory;

@Slf4j
public class AwtBot {
    private static final String debugPath = App.readSetting("app.bot.debugPath");
    private static final Object clickSyncRoot = new Object();

    @SneakyThrows
    public static void init() {
        System.setProperty("java.awt.headless", "false");

        if (Strings.isNullOrEmpty(debugPath)) {
            return;
        }
        Robot bot = new Robot();
        TaskFactory.schedule(() -> saveScreen(bot, null, null), 30 * 1000);
    }

    private static void saveScreen(Robot bot, BufferedImage key, String msg) {
        if (Strings.isNullOrEmpty(debugPath)) {
            return;
        }

        try {
            Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
            log.info("ScreenSize: {}", screenSize);
            App.createDirectory(debugPath);

            String fileName = DateTime.now().toString(DateTime.Formats.skip(2).first());
            BufferedImage screenCapture = bot.createScreenCapture(new Rectangle(screenSize));
            ImageUtil.saveImage(screenCapture, String.format("%s/%s.png", debugPath, fileName));

            if (key == null) {
                return;
            }
            ImageUtil.saveImage(key, String.format("%s/%s_%s.png", debugPath, fileName, msg));
        } catch (Exception e) {
            log.warn("saveScreen", e);
        }
    }

    public String getClipboardString() {
//        bot.delay(20);
        return getClipboard().getString();
    }

    public void setClipboardString(String text) {
        getClipboard().setContent(text);
    }

    private final Robot bot;
    private final Lazy<AwtClipboard> clipboard;
    @Getter
    @Setter
    private volatile int autoDelay;
    private volatile Rectangle screenRectangle;

    public AwtClipboard getClipboard() {
        return clipboard.getValue();
    }

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
        clipboard = new Lazy<>(AwtClipboard::new);
        this.autoDelay = 10;
    }

    public void delay(int delay) {
        bot.delay(delay);
    }

    public void saveScreen(BufferedImage key, String msg) {
        saveScreen(bot, key, msg);
    }

    public Point clickByImage(BufferedImage keyImg) {
        return clickByImage(keyImg, getScreenRectangle());
    }

    public Point clickByImage(BufferedImage keyImg, Rectangle screenRectangle) {
        return clickByImage(keyImg, screenRectangle, true);
    }

    @SneakyThrows
    public Point clickByImage(BufferedImage keyImg, Rectangle screenRectangle, boolean throwOnEmpty) {
        require(keyImg);

        Point point = findScreenPoint(keyImg, screenRectangle);
        if (point == null) {
            if (!throwOnEmpty) {
                return null;
            }
            saveScreen(bot, keyImg, "clickByImage");
            throw new InvalidOperationException("Can not found point");
        }

        point.x += keyImg.getWidth() / 2;
        point.y += keyImg.getHeight() / 2;
        mouseLeftClick(point);
        return point;
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

        Point point = ImageUtil.findPoint(captureScreen(screenRectangle), partImage, false);
        if (point != null) {
            point.x += screenRectangle.x;
            point.y += screenRectangle.y;
        }
        return point;
    }

    public List<Point> findScreenPoints(BufferedImage partImage) {
        return findScreenPoints(partImage, getScreenRectangle());
    }

    public List<Point> findScreenPoints(BufferedImage partImage, Rectangle screenRectangle) {
        require(partImage, screenRectangle);

        return NQuery.of(ImageUtil.findPoints(captureScreen(screenRectangle), partImage)).select(p -> {
            p.x += screenRectangle.x;
            p.y += screenRectangle.y;
            return p;
        }).toList();
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

    public void clickAndAltF4(int x, int y) {
        synchronized (clickSyncRoot) {
            mouseLeftClickInner(x, y);
            bot.delay(autoDelay);
            bot.keyPress(KeyEvent.VK_ALT);
            bot.keyPress(KeyEvent.VK_F4);
            bot.keyRelease(KeyEvent.VK_F4);
            bot.keyRelease(KeyEvent.VK_ALT);
        }
        bot.delay(autoDelay);
    }

    public Point getMouseLocation() {
        return MouseInfo.getPointerInfo().getLocation();
    }

    public void mouseMove(int x, int y) {
        bot.mouseMove(x, y);
        bot.delay(autoDelay);
    }

    public void mouseLeftClick(Point point) {
        mouseLeftClick(point.x, point.y);
    }

    public void mouseLeftClick(int x, int y) {
        mouseLeftClickInner(x, y);
        bot.delay(autoDelay);
    }

    private void mouseLeftClickInner(int x, int y) {
        synchronized (clickSyncRoot) {
            bot.mouseMove(x, y);
            bot.mousePress(InputEvent.BUTTON1_MASK);
            bot.mouseRelease(InputEvent.BUTTON1_MASK);
        }
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

    public void pressSpace() {
        keysPress(KeyEvent.VK_SPACE);
    }

    public void pressEnter() {
        keysPress(KeyEvent.VK_ENTER);
    }

    public void pressCtrlF() {
        bot.keyPress(KeyEvent.VK_CONTROL);
        bot.keyPress(KeyEvent.VK_F);
        bot.keyRelease(KeyEvent.VK_F);
        bot.keyRelease(KeyEvent.VK_CONTROL);
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

    public String keyCopyString() {
        keyCopy();
        return getClipboardString();
    }

    public void keyCopy() {
        bot.keyPress(KeyEvent.VK_CONTROL);
        bot.keyPress(KeyEvent.VK_C);
        bot.keyRelease(KeyEvent.VK_C);
        bot.keyRelease(KeyEvent.VK_CONTROL);
        bot.delay(autoDelay);
    }

    public void keyParseString(String text) {
        setClipboardString(text);
        keyParse();
    }

    public void keyParse() {
        bot.keyPress(KeyEvent.VK_CONTROL);
        bot.keyPress(KeyEvent.VK_V);
        bot.keyRelease(KeyEvent.VK_V);
        bot.keyRelease(KeyEvent.VK_CONTROL);
        bot.delay(autoDelay);
    }
}
