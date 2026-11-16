package io.scent.settlement;

import io.scent.account.*;
import io.scent.advisor.AdvisorService;
import io.scent.common.*;
import io.scent.order.OrderModels;
import io.scent.settlement.SettlementModels.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api/v1")
public class SettlementController {
    private final PaymentService payments;
    private final AfterSaleService sales;
    private final ReviewService reviews;
    private final FinanceService finance;
    private final AttemptLimiter limiter;
    private final AdvisorService advisor;
    public SettlementController(PaymentService payments, AfterSaleService sales, ReviewService reviews,
                                 FinanceService finance, AttemptLimiter limiter, AdvisorService advisor) {
        this.payments = payments; this.sales = sales; this.reviews = reviews; this.finance = finance; this.limiter = limiter; this.advisor = advisor;
    }

    @GetMapping("/capabilities")
    ApiResult<Map<String,Boolean>> capabilities() {
        return ApiResult.ok(Map.of("paymentConfigured", payments.configured(), "advisorConfigured", advisor.configured()));
    }

    @PostMapping("/orders/{id}/payment")
    ApiResult<PaymentLink> begin(@AuthenticationPrincipal Actor actor, @PathVariable long id) {
        limiter.check("payment-issue:" + actor.id(), 10, 60);
        return ApiResult.ok(payments.begin(actor.id(), id));
    }
    @PostMapping("/orders/{id}/payment/sync")
    ApiResult<Detail> sync(@AuthenticationPrincipal Actor actor, @PathVariable long id) {
        limiter.check("payment-sync:" + actor.id(), 20, 60);
        return ApiResult.ok(payments.synchronize(id, actor.id()));
    }
    @GetMapping("/orders/{id}/settlement")
    ApiResult<Detail> detail(@AuthenticationPrincipal Actor actor, @PathVariable long id) { return ApiResult.ok(payments.detail(id, actor.id())); }
    @GetMapping("/admin/orders/{id}/settlement")
    ApiResult<Detail> adminDetail(@PathVariable long id) { return ApiResult.ok(payments.detail(id, null)); }
    @PostMapping("/admin/orders/{id}/payment/sync")
    ApiResult<Detail> adminSync(@AuthenticationPrincipal Actor actor, @PathVariable long id) {
        limiter.check("payment-sync:" + actor.id(), 20, 60);
        return ApiResult.ok(payments.synchronize(id, null));
    }

    @PostMapping(value = "/payments/alipay/notify", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.TEXT_PLAIN_VALUE)
    ResponseEntity<String> notify(HttpServletRequest request) {
        try {
            limiter.check("payment-notify:" + request.getRemoteAddr(), 120, 60);
            Map<String,String[]> source = request.getParameterMap();
            if (source.isEmpty() || source.size() > 40 || request.getContentLengthLong() > 16384) { throw BusinessException.invalid("Invalid notification"); }
            var parameters = new HashMap<String,String>();
            for (var entry : source.entrySet()) {
                String[] values = entry.getValue();
                if (!entry.getKey().matches("[a-z_]{1,64}") || values == null || values.length != 1 || values[0] == null || values[0].length() > 8192) { throw BusinessException.invalid("Invalid notification"); }
                parameters.put(entry.getKey(), values[0]);
            }
            payments.notification(parameters); return ResponseEntity.ok("success");
        } catch (BusinessException ex) { return ResponseEntity.status(ex.status()).body("failure"); }
        catch (RuntimeException ex) {
            LoggerFactory.getLogger(SettlementController.class).warn("Payment notification requires retry type={}", ex.getClass().getSimpleName());
            return ResponseEntity.status(503).body("failure");
        }
    }

    @PostMapping("/orders/{id}/after-sale")
    ApiResult<AfterSale> apply(@AuthenticationPrincipal Actor actor, @PathVariable long id, @Valid @RequestBody Reason input) {
        return ApiResult.ok(sales.apply(actor.id(), id, input.reason()));
    }
    @PostMapping("/after-sales/{id}/return")
    ApiResult<AfterSale> returning(@AuthenticationPrincipal Actor actor, @PathVariable long id, @Valid @RequestBody OrderModels.Ship input) {
        return ApiResult.ok(sales.returning(actor.id(), id, input));
    }
    @GetMapping("/admin/after-sales")
    ApiResult<Page<AfterSale>> sales(@RequestParam(required = false) String state, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "12") int pageSize) {
        return ApiResult.ok(sales.list(state, page, pageSize));
    }
    @PostMapping("/admin/after-sales/{id}/review")
    ApiResult<AfterSale> review(@AuthenticationPrincipal Actor actor, @PathVariable long id, @Valid @RequestBody Decision input) { return ApiResult.ok(sales.review(actor.id(), id, input)); }
    @PostMapping("/admin/after-sales/{id}/receive")
    ApiResult<AfterSale> received(@AuthenticationPrincipal Actor actor, @PathVariable long id) { return ApiResult.ok(sales.received(actor.id(), id)); }
    @PostMapping("/admin/after-sales/{id}/refund")
    ApiResult<Refund> refund(@AuthenticationPrincipal Actor actor, @PathVariable long id) { return ApiResult.ok(sales.refund(actor.id(), id)); }
    @PostMapping("/admin/after-sales/{id}/restock")
    ApiResult<AfterSale> restock(@AuthenticationPrincipal Actor actor, @PathVariable long id, @Valid @RequestBody Restock input) { return ApiResult.ok(sales.restock(actor.id(), id, input.reason())); }

    @PostMapping("/orders/{id}/reviews")
    ApiResult<Review> review(@AuthenticationPrincipal Actor actor, @PathVariable long id, @Valid @RequestBody ReviewInput input) { return ApiResult.ok(reviews.create(actor.id(), id, input)); }
    @GetMapping("/catalog/products/{id}/reviews")
    ApiResult<Page<Review>> reviews(@PathVariable long id, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "12") int pageSize) { return ApiResult.ok(reviews.list(id, page, pageSize)); }
    @GetMapping("/admin/finance")
    ApiResult<Finance> finance(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) { return ApiResult.ok(finance.daily(from, to)); }
}
