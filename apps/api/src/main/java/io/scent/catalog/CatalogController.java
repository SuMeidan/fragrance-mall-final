package io.scent.catalog;

import io.scent.account.Actor;
import io.scent.catalog.CatalogModels.*;
import io.scent.common.*;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class CatalogController {
    private final CatalogService service;
    public CatalogController(CatalogService service) { this.service = service; }

    @GetMapping("/catalog/categories")
    ApiResult<List<CategoryTree>> tree() { return ApiResult.ok(service.tree()); }
    @GetMapping("/admin/categories")
    ApiResult<List<Category>> allCategories() { return ApiResult.ok(service.allCategories()); }
    @GetMapping("/catalog/families")
    ApiResult<List<Family>> families() { return ApiResult.ok(service.families()); }

    @GetMapping("/catalog/home")
    ApiResult<Home> home() { return ApiResult.ok(service.home()); }

    @GetMapping("/catalog/products")
    ApiResult<Page<Product>> list(@RequestParam(required = false) String keyword,
        @RequestParam(required = false) Long categoryId, @RequestParam(required = false) Long familyId,
        @RequestParam(defaultValue = "newest") String sort, @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "12") int pageSize, @RequestParam(defaultValue = "false") boolean unscented) {
        return ApiResult.ok(service.list(keyword, categoryId, familyId, sort, page, pageSize, false, unscented));
    }

    @GetMapping("/admin/products")
    ApiResult<Page<Product>> adminList(@RequestParam(required = false) String keyword,
        @RequestParam(required = false) Long categoryId, @RequestParam(required = false) Long familyId,
        @RequestParam(defaultValue = "newest") String sort, @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "12") int pageSize, @RequestParam(defaultValue = "false") boolean unscented) {
        return ApiResult.ok(service.list(keyword, categoryId, familyId, sort, page, pageSize, true, unscented));
    }

    @GetMapping("/catalog/products/{id}")
    ApiResult<Detail> detail(@PathVariable long id) { return ApiResult.ok(service.detail(id, false)); }
    @GetMapping("/admin/products/{id}")
    ApiResult<Detail> adminDetail(@PathVariable long id) { return ApiResult.ok(service.detail(id, true)); }

    @PostMapping("/admin/products")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResult<Detail> create(@AuthenticationPrincipal Actor actor, @Valid @RequestBody ProductInput body) {
        return ApiResult.created(service.save(actor.id(), null, body));
    }

    @PutMapping("/admin/products/{id}")
    ApiResult<Detail> update(@AuthenticationPrincipal Actor actor, @PathVariable long id, @Valid @RequestBody ProductInput body) {
        return ApiResult.ok(service.save(actor.id(), id, body));
    }

    @PostMapping("/admin/products/{id}/stock")
    ApiResult<Detail> stock(@AuthenticationPrincipal Actor actor, @PathVariable long id, @Valid @RequestBody StockInput body) {
        return ApiResult.ok(service.stock(actor.id(), id, body));
    }

    @PatchMapping("/admin/categories/{id}")
    ApiResult<Void> category(@AuthenticationPrincipal Actor actor, @PathVariable long id, @Valid @RequestBody CategoryInput body) {
        service.category(actor.id(), id, body); return ApiResult.ok(null);
    }
}
