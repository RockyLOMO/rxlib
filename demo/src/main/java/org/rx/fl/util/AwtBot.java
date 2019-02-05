package org.rx.fl.util;

import com.google.common.base.Strings;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.beans.DateTime;
import org.rx.common.InvalidOperationException;
import org.rx.common.NQuery;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;

import static org.rx.common.Contract.isNull;
import static org.rx.common.Contract.require;
import static org.rx.util.AsyncTask.TaskFactory;

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
        if (t == null || !t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
            return null;
        }
        return (String) t.getTransferData(DataFlavor.stringFlavor);
    }
    GlobalScreen
    public void setClipboardString(String text) {
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        clipboard.setContents(new StringSelection(text), null);
    }

    public static void setClipboardImage(Image image) {
        Transferable trans = new Transferable() {
            @Override
            public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException, IOException {
                if (isDataFlavorSupported(flavor)) {
                    return image;
                }
                throw new UnsupportedFlavorException(flavor);
            }

            @Override
            public DataFlavor[] getTransferDataFlavors() {
                return new DataFlavor[]{DataFlavor.imageFlavor};
            }

            @Override
            public boolean isDataFlavorSupported(DataFlavor flavor) {
                return DataFlavor.imageFlavor.equals(flavor);
            }
        };

        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(trans, null);
    }

    private final Robot bot;
    @Getter
    @Setter
    private volatile int autoDelay;
    @Getter
    @Setter
    private volatile String logPath;
    private volatile Rectangle screenRectangle;

    public Rectangle getScreenRectangle() {
        return isNull(screenRectangle, new Rectangle(Toolkit.getDefaultToolkit().getScreenSize()));
    }

    public void setScreenRectangle(Rectangle screenRectangle) {
        require(screenRectangle);

        this.screenRectangle = screenRectangle;
    }

    public AwtBot() {
        this(10, null);
    }

    @SneakyThrows
    public AwtBot(int autoDelay, String logPath) {
        bot = new Robot();
        this.autoDelay = autoDelay;
        this.logPath = logPath;
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
        if (Strings.isNullOrEmpty(logPath)) {
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

    public String getKeyCopyString() {
        keyCopy();
        return AwtBot.getClipboardString();
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
