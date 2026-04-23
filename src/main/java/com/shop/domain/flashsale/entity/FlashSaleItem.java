package com.shop.domain.flashsale.entity;

import com.shop.domain.product.entity.Product;
import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "flash_sale_items")
public class FlashSaleItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "flash_sale_item_id")
    private Long flashSaleItemId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "flash_sale_id", nullable = false)
    private FlashSale flashSale;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "sale_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal salePrice;

    @Column(name = "allocated_quantity", nullable = false)
    private Integer allocatedQuantity;

    @Column(name = "remaining_quantity", nullable = false)
    private Integer remainingQuantity;

    @Column(name = "per_user_limit", nullable = false)
    private Integer perUserLimit;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    protected FlashSaleItem() {}

    public static FlashSaleItem allocate(Product product, BigDecimal salePrice,
                                         int allocatedQuantity, int perUserLimit) {
        if (allocatedQuantity <= 0) {
            throw new IllegalArgumentException("할당 수량은 1 이상이어야 합니다.");
        }
        if (perUserLimit < 1) {
            throw new IllegalArgumentException("1인 구매 한도는 1 이상이어야 합니다.");
        }
        if (salePrice.signum() < 0) {
            throw new IllegalArgumentException("세일 가격은 0 이상이어야 합니다.");
        }
        FlashSaleItem i = new FlashSaleItem();
        i.product = product;
        i.salePrice = salePrice;
        i.allocatedQuantity = allocatedQuantity;
        i.remainingQuantity = allocatedQuantity;
        i.perUserLimit = perUserLimit;
        return i;
    }

    void bindFlashSale(FlashSale flashSale) {
        this.flashSale = flashSale;
    }

    public boolean isSoldOut() {
        return remainingQuantity <= 0;
    }

    public Long getFlashSaleItemId() { return flashSaleItemId; }
    public FlashSale getFlashSale() { return flashSale; }
    public Product getProduct() { return product; }
    public BigDecimal getSalePrice() { return salePrice; }
    public Integer getAllocatedQuantity() { return allocatedQuantity; }
    public Integer getRemainingQuantity() { return remainingQuantity; }
    public Integer getPerUserLimit() { return perUserLimit; }
    public Integer getVersion() { return version; }
}
