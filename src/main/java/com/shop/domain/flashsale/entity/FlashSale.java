package com.shop.domain.flashsale.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "flash_sales")
public class FlashSale {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "flash_sale_id")
    private Long flashSaleId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private FlashSaleStatus status;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalDateTime endTime;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    @OneToMany(mappedBy = "flashSale", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    private List<FlashSaleItem> items = new ArrayList<>();

    protected FlashSale() {}

    public static FlashSale schedule(String title, LocalDateTime startTime, LocalDateTime endTime) {
        if (!endTime.isAfter(startTime)) {
            throw new IllegalArgumentException("종료 시각은 시작 시각 이후여야 합니다.");
        }
        FlashSale s = new FlashSale();
        s.title = title;
        s.status = FlashSaleStatus.SCHEDULED;
        s.startTime = startTime;
        s.endTime = endTime;
        s.createdAt = LocalDateTime.now();
        return s;
    }

    public void activate() {
        if (this.status != FlashSaleStatus.SCHEDULED) {
            throw new IllegalStateException("SCHEDULED 상태에서만 ACTIVE로 전이할 수 있습니다.");
        }
        this.status = FlashSaleStatus.ACTIVE;
    }

    public void end() {
        if (this.status != FlashSaleStatus.ACTIVE) {
            throw new IllegalStateException("ACTIVE 상태에서만 ENDED로 전이할 수 있습니다.");
        }
        this.status = FlashSaleStatus.ENDED;
    }

    public void cancel() {
        if (this.status == FlashSaleStatus.ENDED) {
            throw new IllegalStateException("이미 종료된 세일은 취소할 수 없습니다.");
        }
        this.status = FlashSaleStatus.CANCELLED;
    }

    public boolean isOpenAt(LocalDateTime now) {
        return status == FlashSaleStatus.ACTIVE
                && !now.isBefore(startTime)
                && now.isBefore(endTime);
    }

    public void addItem(FlashSaleItem item) {
        items.add(item);
        item.bindFlashSale(this);
    }

    public Long getFlashSaleId() { return flashSaleId; }
    public String getTitle() { return title; }
    public FlashSaleStatus getStatus() { return status; }
    public LocalDateTime getStartTime() { return startTime; }
    public LocalDateTime getEndTime() { return endTime; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public Integer getVersion() { return version; }
    public List<FlashSaleItem> getItems() { return items; }
}
