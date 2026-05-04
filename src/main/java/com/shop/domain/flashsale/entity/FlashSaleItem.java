package com.shop.domain.flashsale.entity;

import com.shop.domain.product.entity.Product;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

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

    /**
     * [Phase 23-4] 비관적 락 경로(`flash-sale.lock-strategy=pessimistic`)에서
     * SELECT FOR UPDATE로 행을 잡은 뒤 수량을 차감할 때만 사용한다.
     * CAS 경로는 이 메서드를 거치지 않고 JPQL UPDATE로 직접 감분한다.
     */
    public void decreaseRemainingForLockedReserve(int qty) {
        if (qty <= 0) {
            throw new IllegalArgumentException("차감 수량은 1 이상이어야 합니다.");
        }
        if (this.remainingQuantity < qty) {
            throw new IllegalStateException("남은 수량 부족: remaining=" + this.remainingQuantity);
        }
        this.remainingQuantity -= qty;
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
