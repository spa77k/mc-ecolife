package dev.spa.ecolife.poster;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class PosterImageTest {
    @TempDir Path root;

    @Test void fitsWithoutStretchingAndPreservesTileOrder() throws Exception {
        BufferedImage source = new BufferedImage(256, 128, BufferedImage.TYPE_INT_RGB);
        var g = source.createGraphics();
        g.setColor(Color.RED); g.fillRect(0, 0, 128, 128);
        g.setColor(Color.BLUE); g.fillRect(128, 0, 128, 128); g.dispose();
        ImageIO.write(source, "png", root.resolve("sign.png").toFile());
        var image = PosterImage.read(root, "sign.png", 2, 2);
        assertEquals(4, image.tiles().size());
        assertEquals(Color.BLACK.getRGB(), image.tiles().get(0).getRGB(64, 0));
        assertEquals(Color.RED.getRGB(), image.tiles().get(0).getRGB(64, 100));
        assertEquals(Color.BLUE.getRGB(), image.tiles().get(1).getRGB(64, 100));
        assertEquals(Color.RED.getRGB(), image.tiles().get(2).getRGB(64, 0));
        assertEquals(Color.BLACK.getRGB(), image.tiles().get(3).getRGB(64, 127));
        assertEquals(image.key(), PosterImage.read(root, "sign.png", 2, 2).key());
        assertNotEquals(image.key(), PosterImage.read(root, "sign.png", 1, 1).key());
    }

    @Test void rejectsEscapeSymlinkInvalidSizeAndBrokenImage() throws Exception {
        Path images = Files.createDirectory(root.resolve("images"));
        Files.writeString(root.resolve("outside.png"), "not an image");
        Files.createSymbolicLink(images.resolve("link.png"), root.resolve("outside.png"));
        assertThrows(Exception.class, () -> PosterImage.read(images, "../outside.png", 1, 1));
        assertThrows(Exception.class, () -> PosterImage.read(images, "link.png", 1, 1));
        assertThrows(Exception.class, () -> PosterImage.read(images, "link.png", 5, 1));
        Files.writeString(images.resolve("broken.png"), "broken");
        assertThrows(Exception.class, () -> PosterImage.read(images, "broken.png", 1, 1));
    }
}
