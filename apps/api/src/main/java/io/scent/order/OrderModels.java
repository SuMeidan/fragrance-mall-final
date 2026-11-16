package io.scent.order;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class OrderModels {
    private OrderModels() { }
    public record Checkout(@NotNull @Positive Long addressId) { }
    public record Cancel(@Size(max = 200) String reason) { }
    public record Ship(@NotBlank @Size(max = 40) String carrier,
                       @NotNull @Pattern(regexp = "[A-Za-z0-9-]{6,64}") String trackingNo) { }
    public record Item(long id, long orderId, long productId, String name, String spec, String unit,
                       String imagePath, long priceCents, int quantity) {
        public long getSubtotalCents() { return Math.multiplyExact(priceCents, quantity); }
    }
    public record Detail(Order order, List<Item> items) { }
    public record Summary(long id, String orderNo, int status, String statusName, long totalCents,
                          int itemCount, Instant createdAt, Instant expiresAt) { }
    public record Query(Long owner, Integer status, int offset, int size) { }
}
