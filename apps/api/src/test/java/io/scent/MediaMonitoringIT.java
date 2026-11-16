package io.scent;

import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;

class MediaMonitoringIT extends ApiIntegrationSupport {
    private static final Path DIRECTORY=temporary();
    private final Set<Long> mediaProducts=new HashSet<>();
    private static Path temporary() { try{return Files.createTempDirectory("scent-media-it-");}catch(IOException ex){throw new IllegalStateException(ex);} }
    @DynamicPropertySource static void media(DynamicPropertyRegistry properties) { properties.add("scent.media.directory",()->DIRECTORY.toString()); }
    @AfterAll void removeOnlyFixtureImages() throws IOException {
        assertTrue(DIRECTORY.toRealPath().startsWith(Path.of(System.getProperty("java.io.tmpdir")).toRealPath()));
        assertTrue(DIRECTORY.getFileName().toString().startsWith("scent-media-it-"));
        // Later test contexts share this *_it database but use another media directory.
        // Restore only this test's image-bearing products before deleting their files.
        if (!mediaProducts.isEmpty()) {
            assertTrue(Objects.requireNonNull(db.queryForObject("SELECT DATABASE()",String.class)).endsWith("_it"));
            for (long id:mediaProducts) {
                assertEquals(1,db.update("UPDATE product SET image_path='/assets/candle.png',scent_image_path=NULL,scene_image_path=NULL WHERE id=?",id));
                assertEquals("/assets/candle.png",db.queryForObject("SELECT image_path FROM product WHERE id=?",String.class,id));
            }
        }
        try(var files=Files.list(DIRECTORY)){for(Path p:files.toList())Files.delete(p);}Files.delete(DIRECTORY);
    }
    private byte[] image(String format,int width,int height) throws IOException {
        var output=new ByteArrayOutputStream();assertTrue(ImageIO.write(new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB),format,output));return output.toByteArray();
    }
    private HttpResponse<byte[]> upload(String token,byte[] content,String original,String mime) throws Exception {
        String boundary="scent-"+UUID.randomUUID();var output=new ByteArrayOutputStream();
        output.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+original+"\"\r\nContent-Type: "+mime+"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(content);output.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
        var request=HttpRequest.newBuilder(uri("/admin/media")).header("Content-Type","multipart/form-data; boundary="+boundary);
        if(token!=null)request.header("Authorization","Bearer "+token);
        return client.send(request.POST(HttpRequest.BodyPublishers.ofByteArray(output.toByteArray())).build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    @Test void reencodesContentStoresRandomNameAndPubliclyReadsWithSafeHeaders() throws Exception {
        byte[] png=image("png",64,80),trailer="<script>private-tail</script>".getBytes(StandardCharsets.UTF_8);
        var combined=new ByteArrayOutputStream();combined.write(png);combined.write(trailer);
        var response=upload(admin,combined.toByteArray(),"../../escape.svg","text/html");assertEquals(201,response.statusCode());
        String path=json.readTree(response.body()).path("data").path("imagePath").asString();assertTrue(path.matches("/media/[a-f0-9]{32}\\.png"));
        var read=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200,read.statusCode());assertEquals("image/png",read.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("nosniff",read.headers().firstValue("X-Content-Type-Options").orElseThrow());assertTrue(read.headers().firstValue("Cache-Control").orElseThrow().contains("immutable"));
        assertFalse(new String(read.body(),StandardCharsets.ISO_8859_1).contains("private-tail"));assertEquals(64,ImageIO.read(new ByteArrayInputStream(read.body())).getWidth());
        var product=input(101,1L,1);product.put("imagePath",path);long id=create(product);mediaProducts.add(id);
        assertEquals(path,call("GET","/catalog/products/"+id,null,null,200).path("data").path("product").path("imagePath").asString());
        var jpg=upload(admin,image("jpg",40,40),"x.exe","application/octet-stream");assertEquals(201,jpg.statusCode());assertTrue(json.readTree(jpg.body()).path("data").path("imagePath").asString().endsWith(".jpg"));
    }
    @Test void rejectsGuestsBuyersSvgCorruptionDimensionsAndOversize() throws Exception {
        byte[] png=image("png",32,32);assertEquals(401,upload(null,png,"a.png","image/png").statusCode());
        User buyer=buyer();assertEquals(403,upload(buyer.token(),png,"a.png","image/png").statusCode());
        assertEquals(400,upload(admin,"<svg onload='x'/>".getBytes(),"a.png","image/png").statusCode());
        assertEquals(400,upload(admin,image("png",8,8),"a.png","image/png").statusCode());
        assertEquals(400,upload(admin,image("png",4097,32),"a.png","image/png").statusCode());
        assertEquals(400,upload(admin,Arrays.copyOf(png,20),"a.png","image/png").statusCode());
        assertEquals(413,upload(admin,new byte[5*1024*1024+1],"a.png","image/png").statusCode());
    }
    @Test void invalidMediaReferencesAndTraversalCannotBecomeStoredProductPaths() throws Exception {
        var product=input(101,1L,1);product.put("imagePath","/media/"+"a".repeat(32)+".png");call("POST","/admin/products",admin,product,404);
        product.put("imagePath","https://example.com/x.png");call("POST","/admin/products",admin,product,400);
        product.put("imagePath","/media/../application.properties");call("POST","/admin/products",admin,product,400);
        for(String path:List.of("/media/x.svg","/media/"+"b".repeat(32)+".png","/media/%2e%2e/application.properties")) {
            var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString());assertNotEquals(200,response.statusCode());assertFalse(response.body().contains("spring.datasource"));
        }
    }
    @Test void thumbnailIsPublicBoundedAndRetainsRangeAndCacheHeaders() throws Exception {
        var uploaded=upload(admin,image("png",1000,800),"large.png","image/png");assertEquals(201,uploaded.statusCode());
        String path=json.readTree(uploaded.body()).path("data").path("imagePath").asString();
        String url="http://127.0.0.1:"+port+path;
        var preview=client.send(HttpRequest.newBuilder(URI.create(url+"?width=480")).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200,preview.statusCode());assertEquals("image/jpeg",preview.headers().firstValue("Content-Type").orElseThrow());
        assertEquals(480,ImageIO.read(new ByteArrayInputStream(preview.body())).getWidth());
        assertEquals(384,ImageIO.read(new ByteArrayInputStream(preview.body())).getHeight());
        assertTrue(preview.headers().firstValue("Cache-Control").orElseThrow().contains("immutable"));
        var range=client.send(HttpRequest.newBuilder(URI.create(url+"?width=480")).header("Range","bytes=0-9").GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(206,range.statusCode());assertEquals(10,range.body().length);
        for(String width:List.of("0","1000000","bad")) {
            var invalid=client.send(HttpRequest.newBuilder(URI.create(url+"?width="+width)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());assertEquals(400,invalid.statusCode());
        }
        var original=client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertEquals("image/png",original.headers().firstValue("Content-Type").orElseThrow());assertEquals(1000,ImageIO.read(new ByteArrayInputStream(original.body())).getWidth());
    }
    @Test void metricsAreAdminOnlyHealthIsMinimalAndTraceIsServerGenerated() throws Exception {
        var health=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/actuator/health")).header("X-Trace-Id","injected-value").GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,health.statusCode());assertEquals("UP",json.readTree(health.body()).path("status").asString());
        assertFalse(json.readTree(health.body()).has("components"));assertFalse(json.readTree(health.body()).has("details"));assertFalse(health.body().contains("jdbc:"));
        assertNotEquals("injected-value",health.headers().firstValue("X-Trace-Id").orElseThrow());
        String token=buyer().token();
        for(String path:List.of("/actuator/metrics","/actuator/metrics/jvm.memory.used","/actuator/metrics/http.server.requests")) {
            for(String role:List.of("guest","buyer","admin")) {
                var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));if(!role.equals("guest"))request.header("Authorization","Bearer "+(role.equals("admin")?admin:token));
                var response=client.send(request.GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(role.equals("guest")?401:role.equals("buyer")?403:200,response.statusCode(),path+role);
            }
        }
    }

    @Test void threeImagesRoundTripLegacyEditAndExplicitClearKeepOneProduct() throws Exception {
        var paths=new ArrayList<String>();
        for (int i=0;i<3;i++) {
            var uploaded=upload(admin,image("png",64+i*16,80),"member.png","image/png");
            assertEquals(201,uploaded.statusCode()); paths.add(json.readTree(uploaded.body()).path("data").path("imagePath").asString());
        }
        var body=input(101,1L,3); body.put("imagePath",paths.get(0));
        body.put("extraImages",Map.of("scentImagePath",paths.get(1),"sceneImagePath",paths.get(2)));
        long id=create(body);mediaProducts.add(id);
        var saved=call("GET","/catalog/products/"+id,null,null,200).path("data").path("product");
        for (int i=0;i<3;i++) assertEquals(paths.get(i),saved.path(List.of("imagePath","scentImagePath","sceneImagePath").get(i)).asString());
        var home=call("GET","/catalog/home",null,null,200).path("data");
        assertEquals(4,home.size()); assertFalse(home.has("voices"));
        assertTrue(home.path("hero").size()<=3); assertTrue(home.path("gifts").size()<=4);
        assertTrue(home.path("fragrances").size()<=3); assertTrue(home.path("objects").size()<=3);
        for (var name:List.of("gifts","fragrances","objects")) for (var p:home.path(name)) {
            assertTrue(p.path("stock").asInt()>0); assertTrue(p.path("active").asBoolean()); assertFalse(p.has("reviewCount"));
        }
        body.remove("extraImages"); var legacy=edit(body,saved.path("version").asLong());
        var kept=call("PUT","/admin/products/"+id,admin,legacy,200).path("data").path("product");
        assertEquals(paths.get(1),kept.path("scentImagePath").asString()); assertEquals(paths.get(2),kept.path("sceneImagePath").asString());
        var clear=edit(body,kept.path("version").asLong()); var empty=new HashMap<String,Object>();empty.put("scentImagePath",null);empty.put("sceneImagePath",null);clear.put("extraImages",empty);
        var cleared=call("PUT","/admin/products/"+id,admin,clear,200).path("data").path("product");
        assertTrue(cleared.path("scentImagePath").isNull());assertTrue(cleared.path("sceneImagePath").isNull());
        assertEquals(paths.get(0),cleared.path("imagePath").asString()); assertEquals(3,cleared.path("stock").asInt());
        call("PUT","/admin/products/"+id,admin,legacy,409);
        for (String path:paths) assertEquals(200,client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofByteArray()).statusCode());
    }

    @Test void extraImagesRejectExternalMissingAndNonAdministratorWrites() throws Exception {
        var body=input(101,1L,1);
        body.put("extraImages",Map.of("scentImagePath","https://example.com/p.png")); call("POST","/admin/products",admin,body,400);
        body.put("extraImages",Map.of("sceneImagePath","/media/../secret.png"));call("POST","/admin/products",admin,body,400);
        body.put("extraImages",Map.of("sceneImagePath","/media/"+"c".repeat(32)+".png"));call("POST","/admin/products",admin,body,404);
        body.remove("extraImages");call("POST","/admin/products",null,body,401);call("POST","/admin/products",buyer().token(),body,403);
    }
}
