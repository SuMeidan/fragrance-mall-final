package io.scent.settlement;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.Data;

public final class SettlementModels {
    private SettlementModels() { }

    public record Payment(long orderId, @JsonIgnore String appId, @JsonIgnore String sellerId, String status,
                          String tradeNo, Long amountCents, Instant successAt, Instant issuedAt, Instant checkedAt, boolean needsReview) { }
    @Data
    public static class AfterSale {
        private Long id;
        private Long orderId;
        private String reason;
        private String state;
        private String reviewNote;
        private Long reviewedBy;
        private Instant reviewedAt;
        private String returnCarrier;
        private String returnTrackingNo;
        private Instant receivedAt;
        private Boolean restocked;
        private Instant createdAt;
        private String orderNo;
        private Integer orderStatus;
        private Long totalCents;
    }
    public record Refund(long afterSaleId, String requestNo, String status, long amountCents,
                         Instant successAt, String lastErrorCode, Instant updatedAt) { }
    public record Review(long id, long orderItemId, int stars, String content, Instant createdAt, String displayName) { }
    public record Detail(Payment payment, AfterSale afterSale, Refund refund, List<Review> reviews) { }
    public record PaymentLink(String url) { }
    public record Reason(@NotBlank @Size(max = 500) String reason) { }
    public record Decision(@NotNull Boolean approve, @NotBlank @Size(max = 500) String note) { }
    public record Restock(@NotBlank @Size(max = 120) String reason) { }
    public record ReviewInput(@Positive long orderItemId, @Min(1) @Max(5) int stars,
                              @NotBlank @Size(max = 1000) String content) { }
    public record Daily(LocalDate date, long paidCents, long refundedCents, long netCents, long paymentCount, long refundCount) { }
    public record Finance(LocalDate from, LocalDate to, List<Daily> days, long paidCents, long refundedCents, long netCents) { }
    public record Total(LocalDate date, long cents, long count) { }
}
