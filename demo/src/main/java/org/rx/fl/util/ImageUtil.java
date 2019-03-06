package org.rx.fl.util;

import lombok.SneakyThrows;
import net.glxn.qrgen.core.image.ImageType;
import net.glxn.qrgen.javase.QRCode;
import org.rx.common.App;
import org.rx.common.InvalidOperationException;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.ArrayList;
import java.util.List;

import static org.rx.common.Contract.require;

public class ImageUtil {
    public static final String ImageFormat = "png";
    public static final String Base64ImagePrefix = "data:image/png;base64, ";

    @SneakyThrows
    public static BufferedImage createQRCodeImage(int imageSize, String param) {
        return ImageIO.read(new ByteArrayInputStream(createQRCodeImageBytes(imageSize, param)));
    }

    public static String createQRCodeImageBase64(int imageSize, String param) {
        return createQRCodeImageBase64(createQRCodeImageBytes(imageSize, param));
    }

    public static String createQRCodeImageBase64(byte[] bytes) {
        return Base64ImagePrefix + App.convertToBase64String(bytes);
    }

    @SneakyThrows
    public static byte[] createQRCodeImageBytes(int imageSize, String param) {
        try (ByteArrayOutputStream stream = new ByteArrayOutputStream()) {
            QRCode.from(param).to(ImageType.PNG).withSize(imageSize, imageSize).writeTo(stream);
            return stream.toByteArray();
        }
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

    @SneakyThrows
    public static BufferedImage loadImage(String filePath) {
        require(filePath);

        return ImageIO.read(new File(filePath));
    }

    public static BufferedImage loadImageBase64(String base64Image) {
        require(base64Image);
        require(base64Image, base64Image.startsWith(Base64ImagePrefix));

        return loadImage(App.convertFromBase64String(base64Image.substring(Base64ImagePrefix.length())));
    }

    @SneakyThrows
    public static BufferedImage loadImage(byte[] bytes) {
        require(bytes);

        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    @SneakyThrows
    public static void saveImage(BufferedImage img, String filePath) {
        require(img, filePath);

        ImageIO.write(img, ImageFormat, new File(filePath));
    }

    public static Point findPoint(BufferedImage image, BufferedImage partImage, boolean throwOnEmpty) {
        require(image, partImage);

        int w = image.getWidth() - partImage.getWidth(), h = image.getHeight() - partImage.getHeight();
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                if (partEquals(image, x, y, partImage)) {
                    return new Point(x, y);
                }
            }
        }

        if (throwOnEmpty) {
            throw new InvalidOperationException("Can not found point");
        }
        return null;
    }

    public static List<Point> findPoints(BufferedImage image, BufferedImage partImage) {
        require(image, partImage);

        List<Point> points = new ArrayList<>();
        int w = image.getWidth() - partImage.getWidth(), h = image.getHeight() - partImage.getHeight();
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                if (partEquals(image, x, y, partImage)) {
                    points.add(new Point(x, y));
                }
            }
        }
        return points;
    }

    public static boolean partEquals(BufferedImage image, int imageX, int imageY, BufferedImage partImage) {
        require(image, partImage);

        int partW = partImage.getWidth();
        int partH = partImage.getHeight();
        for (int x = 0; x < partW; x++) {
            for (int y = 0; y < partH; y++) {
                if (image.getRGB(imageX + x, imageY + y) != partImage.getRGB(x, y)) {
                    return false;
                }
            }
        }
        return true;
    }
}
