package io.scent.media;

import io.scent.common.*;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class MediaService {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(MediaService.class);
    private final Path directory;
    private final AuditService audit;
    public MediaService(@Value("${scent.media.directory:}") String directory, AuditService audit) {
        this.directory = directory.isBlank() ? null : Path.of(directory).toAbsolutePath().normalize(); this.audit = audit;
        if (this.directory != null && (!Path.of(directory).isAbsolute() || Files.isSymbolicLink(this.directory))) { throw new IllegalStateException("Use an absolute private media directory"); }
    }

    public String upload(long actor, MultipartFile file) throws IOException {
        if (directory == null) { throw new BusinessException(503,"MEDIA_UNAVAILABLE","图片上传尚未配置"); }
        if (file == null || file.isEmpty() || file.getSize() > 5 * 1024 * 1024) { throw new BusinessException(413,"UPLOAD_TOO_LARGE","请选择5MiB以内的PNG或JPEG图片"); }
        byte[] bytes;
        try (InputStream stream=file.getInputStream()) { bytes=stream.readNBytes(5*1024*1024+1); }
        if (bytes.length>5*1024*1024) { throw new BusinessException(413,"UPLOAD_TOO_LARGE","图片过大"); }
        String format;
        if (bytes.length>=8 && bytes[0]==(byte)0x89 && bytes[1]=='P' && bytes[2]=='N' && bytes[3]=='G' && bytes[4]==13 && bytes[5]==10 && bytes[6]==26 && bytes[7]==10) format="png";
        else if (bytes.length>=3 && bytes[0]==(byte)0xff && bytes[1]==(byte)0xd8 && bytes[2]==(byte)0xff) format="jpg";
        else { throw BusinessException.invalid("仅支持真实PNG/JPEG图片，不支持SVG或其他文件"); }
        BufferedImage image;
        try (var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers=ImageIO.getImageReaders(input);
            if (!readers.hasNext()) { throw BusinessException.invalid("无法读取图片"); }
            ImageReader reader=readers.next();
            try {
                reader.setInput(input,true,true);
                int width=reader.getWidth(0),height=reader.getHeight(0);
                if (width<32 || height<32 || width>4096 || height>4096 || (long)width*height>16000000) { throw BusinessException.invalid("图片宽高须为32—4096像素，总像素不超过1600万"); }
                image=reader.read(0);
            } finally { reader.dispose(); }
        } catch (javax.imageio.IIOException ex) { throw BusinessException.invalid("图片损坏，请重新导出PNG/JPEG"); }
        String name=UUID.randomUUID().toString().replace("-","")+"."+format;
        Files.createDirectories(directory);
        Path target=directory.resolve(name);
        try (OutputStream output=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW)) {
            if (!ImageIO.write(image,format,output)) { throw new IOException("Image encoder unavailable"); }
        } catch (IOException ex) { Files.deleteIfExists(target); throw ex; }
        if (Files.size(target)>5*1024*1024) { Files.deleteIfExists(target); throw new BusinessException(413,"UPLOAD_TOO_LARGE","图片重新编码后仍过大，请缩小后重试"); }
        try { audit.write(actor,"MEDIA_UPLOAD","MEDIA",null,"Image stored as "+name); }
        catch (RuntimeException ex) { Files.deleteIfExists(target); throw ex; }
        return "/media/"+name;
    }

    public Path resolve(String name) {
        if (directory==null || name==null || !name.matches("[a-f0-9]{32}\\.(png|jpg)")) { throw BusinessException.missing(); }
        Path path=directory.resolve(name).normalize();
        if (!path.getParent().equals(directory) || Files.isSymbolicLink(path) || !Files.isRegularFile(path)) { throw BusinessException.missing(); }
        return path;
    }
    public Path resolveDisplay(String name,Integer width) {
        Path original=resolve(name);
        if(width==null)return original;
        if(width!=480 && width!=960)throw BusinessException.invalid("图片宽度仅支持480或960像素");
        try{return thumbnail(original,width);}
        catch(IOException ex){LOG.warn("MEDIA_THUMBNAIL_FALLBACK name={} width={}",name,width);return original;}
    }

    // One encoder at a time bounds decoded-image memory on the small deployment host.
    private synchronized Path thumbnail(Path original,int width) throws IOException {
        String base=original.getFileName().toString().replaceFirst("\\.[^.]+$","");
        Path jpg=directory.resolve(base+".v1-"+width+".jpg"),png=directory.resolve(base+".v1-"+width+".png");
        for(Path cached:List.of(jpg,png)) {
            if(Files.isSymbolicLink(cached))throw BusinessException.missing();
            if(Files.isRegularFile(cached,LinkOption.NOFOLLOW_LINKS))return cached;
        }
        BufferedImage image=ImageIO.read(original.toFile());
        if(image==null)throw new IOException("Image cannot be decoded");
        if(image.getWidth()<=width)return original;
        int height=Math.max(1,(int)Math.round((double)image.getHeight()*width/image.getWidth()));
        boolean alpha=image.getColorModel().hasAlpha();
        var scaled=new BufferedImage(width,height,alpha?BufferedImage.TYPE_INT_ARGB:BufferedImage.TYPE_INT_RGB);
        var graphics=scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(image,0,0,width,height,null);
        } finally {graphics.dispose();}
        Path cached=alpha?png:jpg,temp=Files.createTempFile(directory,".thumbnail-",".tmp");
        try {
            if(alpha) {
                if(!ImageIO.write(scaled,"png",temp.toFile()))throw new IOException("PNG encoder unavailable");
            } else {
                var writer=ImageIO.getImageWritersByFormatName("jpg").next();
                try(var output=ImageIO.createImageOutputStream(temp.toFile())) {
                    writer.setOutput(output);
                    var options=writer.getDefaultWriteParam();
                    options.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    options.setCompressionQuality(0.82f);
                    writer.write(null,new IIOImage(scaled,null,null),options);
                } finally {writer.dispose();}
            }
            try {Files.move(temp,cached,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException ex){Files.move(temp,cached);}
            return cached;
        } finally {Files.deleteIfExists(temp);}
    }
    public void requireReference(String imagePath) { if (imagePath.startsWith("/media/")) resolve(imagePath.substring(7)); }
}
