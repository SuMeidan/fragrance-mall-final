package io.scent.order;

import io.scent.account.Actor;
import io.scent.common.*;
import io.scent.order.OrderModels.*;
import io.scent.settlement.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class OrderController {
    private final OrderService service;
    private final PaymentService payments;
    public OrderController(OrderService service, PaymentService payments) { this.service = service; this.payments = payments; }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResult<Detail> checkout(@AuthenticationPrincipal Actor actor, @Valid @RequestBody Checkout body,
                              @RequestHeader("Idempotency-Key") String key) {
        return ApiResult.created(service.checkout(actor.id(), body.addressId(), key));
    }

    @GetMapping("/orders")
    ApiResult<Page<Summary>> list(@AuthenticationPrincipal Actor actor, @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "12") int pageSize) {
        return ApiResult.ok(service.list(actor.id(), status, page, pageSize));
    }

    @GetMapping("/orders/{id}")
    ApiResult<Detail> detail(@AuthenticationPrincipal Actor actor, @PathVariable long id) { return ApiResult.ok(service.detail(id, actor.id())); }

    @PostMapping("/orders/{id}/cancel")
    ApiResult<Detail> cancel(@AuthenticationPrincipal Actor actor, @PathVariable long id, @Valid @RequestBody(required = false) Cancel body) {
        return ApiResult.ok(payments.cancel(id, actor.id(), actor.id(), OrderState.BUYER_CLOSED.code(), reason(body, "买家取消")));
    }

    @PostMapping("/orders/{id}/receive")
    ApiResult<Detail> receive(@AuthenticationPrincipal Actor actor, @PathVariable long id) { return ApiResult.ok(service.receive(actor.id(), id)); }

    @GetMapping("/admin/orders")
    ApiResult<Page<Summary>> adminList(@RequestParam(required = false) Integer status, @RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "12") int pageSize) {
        return ApiResult.ok(service.list(null, status, page, pageSize));
    }

    @GetMapping("/admin/orders/{id}")
    ApiResult<Detail> adminDetail(@PathVariable long id) { return ApiResult.ok(service.detail(id, null)); }

    @PostMapping("/admin/orders/{id}/cancel")
    ApiResult<Detail> adminCancel(@AuthenticationPrincipal Actor actor, @PathVariable long id, @Valid @RequestBody(required = false) Cancel body) {
        return ApiResult.ok(payments.cancel(id, null, actor.id(), OrderState.ADMIN_CLOSED.code(), reason(body, "管理员关闭")));
    }

    @PostMapping("/admin/orders/{id}/pack")
    ApiResult<Detail> pack(@AuthenticationPrincipal Actor actor, @PathVariable long id) { return ApiResult.ok(service.pack(actor.id(), id)); }

    @PostMapping("/admin/orders/{id}/ship")
    ApiResult<Detail> ship(@AuthenticationPrincipal Actor actor, @PathVariable long id, @Valid @RequestBody Ship body) {
        return ApiResult.ok(service.ship(actor.id(), id, body));
    }

    private static String reason(Cancel input, String fallback) {
        return input == null || input.reason() == null || input.reason().isBlank() ? fallback : input.reason().strip();
    }
}
