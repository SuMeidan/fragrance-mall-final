package io.scent.catalog;

import jakarta.validation.constraints.*;
import jakarta.validation.Valid;
import java.util.List;

public final class CatalogModels {
    private CatalogModels() { }

    public record Category(long id, Long parentId, String code, String name, String inputView, int sort, boolean enabled) { }
    public record CategoryTree(long id, String code, String name, List<Category> children) { }
    public record Family(long id, String code, String name, String description, int sort) { }
    public record Query(String keyword, Long categoryId, Long familyId, String sort, int offset, int size, boolean admin, boolean unscented) { }
    public record AdvisorQuery(String keyword, Long categoryId, Long familyId, Long minPriceCents,
                               Long maxPriceCents, int offset, int size) { }
    public record Detail(Product product, List<Product> compatibleProducts) { }
    public record Hero(String key, Product product) { }
    public record Home(List<Hero> hero, List<Product> gifts, List<Product> fragrances, List<Product> objects) { }
    public record ExtraImages(
        @Pattern(regexp = "/media/[a-f0-9]{32}\\.(?:png|jpg)") @Size(max = 240) String scentImagePath,
        @Pattern(regexp = "/media/[a-f0-9]{32}\\.(?:png|jpg)") @Size(max = 240) String sceneImagePath
    ) { }

    public record ProductInput(
        @NotNull @Positive Long categoryId, @Positive Long familyId,
        @NotBlank @Size(max = 100) String name, @Size(max = 160) String subtitle,
        @NotBlank @Size(max = 80) String spec, @NotBlank @Size(max = 16) String unit,
        @NotNull @Min(1) @Max(100000000) Long priceCents,
        @Min(0) @Max(1000000) Integer initialStock, @NotNull Boolean active, @Min(0) Long version,
        @NotBlank @Pattern(regexp = "(?:/assets/[A-Za-z0-9_/-]+\\.(?:png|jpg|jpeg|webp|svg)|/media/[a-f0-9]{32}\\.(?:png|jpg))") @Size(max = 240) String imagePath,
        @NotBlank @Size(max = 4000) String description, @Size(max = 1500) String usageNotes,
        @Size(max = 200) String material, @Size(max = 200) String powerInfo, @Size(max = 1500) String contents,
        @Size(max = 20) List<@NotNull @Positive Long> compatibleIds,
        @Valid ExtraImages extraImages
    ) {
        public ProductInput {
            subtitle = subtitle == null ? "" : subtitle;
            usageNotes = usageNotes == null ? "" : usageNotes;
            material = material == null ? "" : material;
            powerInfo = powerInfo == null ? "" : powerInfo;
            contents = contents == null ? "" : contents;
            compatibleIds = compatibleIds == null ? List.of() : List.copyOf(compatibleIds);
        }
    }

    public record StockInput(@NotNull @Min(0) Long version, @NotNull @Min(-1000000) @Max(1000000) Integer delta,
                             @NotBlank @Size(max = 120) String reason) { }
    public record CategoryInput(@NotBlank @Size(max = 40) String name, @Min(0) @Max(9999) int sort,
                                @NotNull Boolean enabled) { }
}
