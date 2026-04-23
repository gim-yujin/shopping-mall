package com.shop.domain.flashsale.repository;

import com.shop.domain.flashsale.entity.FlashSale;
import com.shop.domain.flashsale.entity.FlashSaleStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface FlashSaleRepository extends JpaRepository<FlashSale, Long> {

    /**
     * 진행중(ACTIVE) + 예정(SCHEDULED, 24시간 이내) 세일 목록.
     * idx_flash_sale_status_start (status, start_time) 범위 스캔.
     */
    @Query("SELECT fs FROM FlashSale fs "
         + "WHERE (fs.status = com.shop.domain.flashsale.entity.FlashSaleStatus.ACTIVE) "
         + "   OR (fs.status = com.shop.domain.flashsale.entity.FlashSaleStatus.SCHEDULED "
         + "       AND fs.startTime < :upcomingCutoff) "
         + "ORDER BY fs.startTime ASC")
    List<FlashSale> findActiveAndUpcoming(@Param("upcomingCutoff") LocalDateTime upcomingCutoff);

    Optional<FlashSale> findByFlashSaleIdAndStatusNot(Long flashSaleId, FlashSaleStatus excludedStatus);
}
