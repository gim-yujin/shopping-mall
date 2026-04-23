package com.shop.domain.flashsale.repository;

import com.shop.domain.flashsale.entity.FlashSaleItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FlashSaleItemRepository extends JpaRepository<FlashSaleItem, Long> {

    /**
     * 세일 목록 페이지에서 상품·재고 정보까지 한 번에 가져온다.
     * 리스트 카드 렌더링 시 N+1 회피 목적.
     */
    @Query("SELECT fsi FROM FlashSaleItem fsi "
         + "JOIN FETCH fsi.product p "
         + "WHERE fsi.flashSale.flashSaleId IN :flashSaleIds")
    List<FlashSaleItem> findAllByFlashSaleIdInWithProduct(@Param("flashSaleIds") List<Long> flashSaleIds);

    @Query("SELECT fsi FROM FlashSaleItem fsi "
         + "JOIN FETCH fsi.product p "
         + "WHERE fsi.flashSale.flashSaleId = :flashSaleId")
    List<FlashSaleItem> findAllByFlashSaleIdWithProduct(@Param("flashSaleId") Long flashSaleId);
}
