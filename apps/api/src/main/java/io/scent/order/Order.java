package io.scent.order;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import lombok.Data;

@Data
public class Order {
    private Long id;
    private String orderNo;
    private Long accountId;
    @JsonIgnore private String checkoutKey;
    @JsonIgnore private Long checkoutAddressId;
    private Integer status;
    private Long totalCents;
    private String receiver;
    private String phone;
    private String region;
    private String addressDetail;
    private String carrier;
    private String trackingNo;
    private String closeReason;
    private Instant createdAt;
    private Instant expiresAt;
    private Instant paidAt;
    private Instant shippedAt;
    private Instant completedAt;
    private Instant closedAt;
    private Integer itemCount;

    public String getStatusName() { return OrderState.label(status); }
}
