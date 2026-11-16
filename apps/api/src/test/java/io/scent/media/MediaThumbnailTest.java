package io.scent.media;

import io.scent.common.BusinessException;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.Random;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MediaThumbnailTest {
    @TempDir Path directory;
    private final String name="a".repeat(32)+".png";
    private MediaService service(){return new MediaService(directory.toString(),null);}

    @Test void boundsBothSizesPreservesOriginalAndReusesTheCachedFile() throws Exception {
        var source=new BufferedImage(1200,900,BufferedImage.TYPE_INT_RGB);
        var random=new Random(17);
        for(int y=0;y<900;y++)for(int x=0;x<1200;x++)source.setRGB(x,y,random.nextInt(0x1000000));
        Path original=directory.resolve(name);assertTrue(ImageIO.write(source,"png",original.toFile()));
        byte[] before=Files.readAllBytes(original);MediaService service=service();
        for(int width:new int[]{480,960}) {
            Path path=service.resolveDisplay(name,width);var decoded=ImageIO.read(path.toFile());
            assertEquals(width,decoded.getWidth());assertEquals(width*3/4,decoded.getHeight());
            assertTrue(Files.size(path)<before.length/3,"Photographic thumbnails should reduce transport bytes");
            var modified=Files.getLastModifiedTime(path);
            assertEquals(path,service.resolveDisplay(name,width));assertEquals(modified,Files.getLastModifiedTime(path));
        }
        assertArrayEquals(before,Files.readAllBytes(original));assertEquals(original,service.resolveDisplay(name,null));
    }

    @Test void keepsTransparencyAndDoesNotUpscaleSmallUploads() throws Exception {
        var transparent=new BufferedImage(1000,800,BufferedImage.TYPE_INT_ARGB);
        transparent.setRGB(500,400,0x80ff0000);
        assertTrue(ImageIO.write(transparent,"png",directory.resolve(name).toFile()));
        Path preview=service().resolveDisplay(name,480);
        var decoded=ImageIO.read(preview.toFile());
        assertTrue(decoded.getColorModel().hasAlpha());assertEquals(0,decoded.getRGB(0,0)>>>24);
        String small="b".repeat(32)+".png";
        ImageIO.write(new BufferedImage(64,80,BufferedImage.TYPE_INT_RGB),"png",directory.resolve(small).toFile());
        assertEquals(directory.resolve(small),service().resolveDisplay(small,480));
    }

    @Test void rejectsUnboundedWidthsAndCannotFollowCacheSymlinks() throws Exception {
        ImageIO.write(new BufferedImage(1000,800,BufferedImage.TYPE_INT_RGB),"png",directory.resolve(name).toFile());
        var service=service();
        for(int width:new int[]{0,1,479,961,Integer.MAX_VALUE})assertThrows(BusinessException.class,()->service.resolveDisplay(name,width));
        assertThrows(BusinessException.class,()->service.resolveDisplay("../secret.png",480));
        // Ordinary Windows users cannot create symlinks; Linux CI covers the guard.
        if(!System.getProperty("os.name").startsWith("Windows")) {
            Path outside=directory.resolve("private.txt");Files.writeString(outside,"private");
            Files.createSymbolicLink(directory.resolve("a".repeat(32)+".v1-480.jpg"),outside);
            assertThrows(BusinessException.class,()->service.resolveDisplay(name,480));
        }
    }
}
