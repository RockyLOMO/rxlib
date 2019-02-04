package org.rx.fl.util;

import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
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
import java.io.InputStream;

import static org.rx.common.Contract.isNull;
import static org.rx.common.Contract.require;

@Slf4j
public class AwtBot {
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

    @SneakyThrows
    public static BufferedImage getImageFromResource(Class owner, String resourceName) {
        require(owner, resourceName);
        InputStream stream = owner.getResourceAsStream(resourceName);
        if (stream == null) {
            throw new InvalidOperationException(String.format("%s missing", resourceName));
        }

        return ImageIO.read(stream);
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
    private volatile Rectangle screenRectangle;

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
        require(keyImg);
        Point point = getScreenPoint(keyImg);
        if (point == null) {
            throw new InvalidOperationException("Point not found");
        }

        int x = point.x + keyImg.getWidth() / 2, y = point.y + keyImg.getHeight() / 2;
        mouseLeftClick(x, y);
    }

    @SneakyThrows
    public Point getScreenPoint(String imageFile) {
        require(imageFile);

        return getScreenPoint(ImageIO.read(new File(imageFile)));
    }

    public Point getScreenPoint(BufferedImage partImage) {
        require(partImage);

        BufferedImage screenImage = captureScreen();
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

    public BufferedImage captureScreen() {
        return bot.createScreenCapture(getScreenRectangle());
    }

    public BufferedImage captureScreen(int x, int y, int width, int height) {
        return bot.createScreenCapture(new Rectangle(x, y, width, height));
    }
}
