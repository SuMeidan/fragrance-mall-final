package io.scent.media;

import io.scent.account.*;
import io.scent.common.ApiResult;
import io.scent.common.BusinessException;
import java.io.IOException;
import java.util.Map;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class MediaController {
    private final MediaService service;
    private final AttemptLimiter limiter;
    public MediaController(MediaService service,AttemptLimiter limiter) { this.service=service;this.limiter=limiter; }
    @PostMapping(value="/api/v1/admin/media",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<ApiResult<Map<String,String>>> upload(@AuthenticationPrincipal Actor actor,@RequestParam MultipartFile file) throws IOException {
        limiter.check("upload:"+actor.id(),20,60);
        return ResponseEntity.status(201).body(ApiResult.created(Map.of("imagePath",service.upload(actor.id(),file))));
    }
    @GetMapping("/media/{name}")
    ResponseEntity<FileSystemResource> image(@PathVariable String name,@RequestParam(required=false) String width) {
        Integer size=width==null?null:switch(width) {
            case "480" -> 480;
            case "960" -> 960;
            default -> throw BusinessException.invalid("图片宽度仅支持480或960像素");
        };
        var path=service.resolveDisplay(name,size);
        var resource=new FileSystemResource(path);
        return ResponseEntity.ok().contentType(path.toString().endsWith(".png")?MediaType.IMAGE_PNG:MediaType.IMAGE_JPEG)
            .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(365)).cachePublic().immutable())
            .header("X-Content-Type-Options","nosniff").body(resource);
    }
}
