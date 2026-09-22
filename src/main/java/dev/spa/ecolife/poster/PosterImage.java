package dev.spa.ecolife.poster;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** 入力を制限し、縦横比を保って額縁用の128pxタイルにする。 */
final class PosterImage {
    record Prepared(String key, List<BufferedImage> tiles) {}

    static Prepared read(Path directory, String filename, int columns, int rows) throws Exception {
        if (columns < 1 || columns > 4 || rows < 1 || rows > 4)
            throw new IOException("サイズは縦横それぞれ1〜4です");
        Path root = directory.toRealPath();
        Path file = root.resolve(filename).normalize().toRealPath();
        if (!file.startsWith(root) || !Files.isRegularFile(file))
            throw new IOException("imagesフォルダー内の画像を指定してください");
        if (Files.size(file) > 10 * 1024 * 1024) throw new IOException("画像は10MB以下にしてください");
        byte[] bytes = Files.readAllBytes(file);
        BufferedImage original;
        try (var input = ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("PNGまたはJPEG画像を指定してください");
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName();
                if (!format.equalsIgnoreCase("png") && !format.equalsIgnoreCase("jpeg"))
                    throw new IOException("PNGまたはJPEG画像を指定してください");
                reader.setInput(input);
                if ((long) reader.getWidth(0) * reader.getHeight(0) > 16_000_000)
                    throw new IOException("画像は1600万画素以下にしてください");
                original = reader.read(0);
            } finally { reader.dispose(); }
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update((byte) columns);
        digest.update((byte) rows);
        String key = HexFormat.of().formatHex(digest.digest(bytes));
        return new Prepared(key, split(original, columns, rows));
    }

    static List<BufferedImage> split(BufferedImage original, int columns, int rows) {
        int width = columns * 128, height = rows * 128;
        BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = canvas.createGraphics();
        graphics.setColor(Color.BLACK);
        graphics.fillRect(0, 0, width, height);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        double scale = Math.min((double) width / original.getWidth(), (double) height / original.getHeight());
        int fittedWidth = Math.max(1, (int) Math.round(original.getWidth() * scale));
        int fittedHeight = Math.max(1, (int) Math.round(original.getHeight() * scale));
        graphics.drawImage(original, (width - fittedWidth) / 2, (height - fittedHeight) / 2,
                fittedWidth, fittedHeight, null);
        graphics.dispose();
        List<BufferedImage> tiles = new ArrayList<>();
        for (int y = 0; y < rows; y++) for (int x = 0; x < columns; x++)
            tiles.add(canvas.getSubimage(x * 128, y * 128, 128, 128));
        return List.copyOf(tiles);
    }
}
