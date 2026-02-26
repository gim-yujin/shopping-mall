package com.shop.domain.product.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

/**
 * 관리자 상품 등록/수정 요청 DTO.
 */
public class AdminProductRequest {

    @NotBlank(message = "상품명을 입력해주세요.")
    @Size(max = 200, message = "상품명은 200자 이하로 입력해주세요.")
    private String productName;

    @NotNull(message = "카테고리를 선택해주세요.")
    private Integer categoryId;

    private String description;

    @NotNull(message = "판매가를 입력해주세요.")
    @DecimalMin(value = "0", inclusive = false, message = "판매가는 0보다 커야 합니다.")
    @Digits(integer = 10, fraction = 2, message = "판매가 형식이 올바르지 않습니다.")
    private BigDecimal price;

    @DecimalMin(value = "0", inclusive = false, message = "원가는 0보다 커야 합니다.")
    @Digits(integer = 10, fraction = 2, message = "원가 형식이 올바르지 않습니다.")
    private BigDecimal originalPrice;

    @NotNull(message = "재고 수량을 입력해주세요.")
    @Min(value = 0, message = "재고 수량은 0 이상이어야 합니다.")
    private Integer stockQuantity;

    public AdminProductRequest() {}

    // Getters & Setters (Spring MVC 폼 바인딩용)
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }

    public Integer getCategoryId() { return categoryId; }
    public void setCategoryId(Integer categoryId) { this.categoryId = categoryId; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    public BigDecimal getOriginalPrice() { return originalPrice; }
    public void setOriginalPrice(BigDecimal originalPrice) { this.originalPrice = originalPrice; }

    public Integer getStockQuantity() { return stockQuantity; }
    public void setStockQuantity(Integer stockQuantity) { this.stockQuantity = stockQuantity; }
}
