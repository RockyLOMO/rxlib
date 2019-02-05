package org.rx.fl.util;

import lombok.SneakyThrows;
import org.rx.common.InvalidOperationException;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.rx.common.Contract.require;

public class ImageUtil {
    public static final String ImageFormat = "png";

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
