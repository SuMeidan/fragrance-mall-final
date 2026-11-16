package io.scent.account;

import lombok.Data;

@Data
public class Address {
    private Long id;
    private Long accountId;
    private String receiver;
    private String phone;
    private String region;
    private String detail;
    private Boolean isDefault;
}
