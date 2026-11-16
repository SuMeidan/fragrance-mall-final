package io.scent.cart;

import io.scent.account.Actor;
import io.scent.common.ApiResult;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/cart")
public class CartController {
    private final CartService service;
    public CartController(CartService service) { this.service = service; }

    @GetMapping
    ApiResult<CartModels.View> view(@AuthenticationPrincipal Actor actor) { return ApiResult.ok(service.view(actor.id())); }

    @PostMapping
    ApiResult<CartModels.View> add(@AuthenticationPrincipal Actor actor, @Valid @RequestBody CartModels.Add body) {
        return ApiResult.ok(service.add(actor.id(), body.productId()));
    }

    @PatchMapping("/{productId}")
    ApiResult<CartModels.View> quantity(@AuthenticationPrincipal Actor actor, @PathVariable long productId,
                                      @Valid @RequestBody CartModels.Quantity body) {
        return ApiResult.ok(service.quantity(actor.id(), productId, body.quantity()));
    }

    @DeleteMapping("/{productId}")
    ApiResult<CartModels.View> remove(@AuthenticationPrincipal Actor actor, @PathVariable long productId) {
        return ApiResult.ok(service.remove(actor.id(), productId));
    }
}
