package io.scent.cart;

import jakarta.validation.constraints.*;
import java.util.List;

public final class CartModels {
    private CartModels() { }

    public record Line(long productId, int quantity) { }
    public record Item(long productId, String name, String spec, String unit, String imagePath,
                       long priceCents, int stock, int quantity, boolean available) {
        public long getSubtotalCents() { return Math.multiplyExact(priceCents, quantity); }
    }
    public record View(List<Item> items, long totalCents, boolean canCheckout) { }
    public record Add(@NotNull @Positive Long productId) { }
    public record Quantity(@NotNull @Min(1) @Max(5) Integer quantity) { }
}
