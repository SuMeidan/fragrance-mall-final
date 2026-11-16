package io.scent.catalog;

import java.time.Instant;
import lombok.Data;

/** Fixed sale unit; all money is integer CNY cents, not the upstream seed's yuan values. */
@Data
public class Product {
    private Long id;
    private Long categoryId;
    private Long familyId;
    private String name;
    private String subtitle;
    private String spec;
    private String unit;
    private Long priceCents;
    private Integer stock;
    private Boolean active;
    private Long version;
    private String imagePath;
    private String scentImagePath;
    private String sceneImagePath;
    private String description;
    private String usageNotes;
    private String material;
    private String powerInfo;
    private String contents;
    private Instant createdAt;
    private Instant updatedAt;
    private String categoryName;
    private String inputView;
    private String familyName;
}
