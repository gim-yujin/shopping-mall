package com.shop.domain.flashsale.repository;

import com.shop.domain.flashsale.entity.FlashSaleItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

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

    // [Phase 23-2] 구매 재검 경로: sale × item 매칭 검증을 겸한 단건 조회.
    @Query("SELECT fsi FROM FlashSaleItem fsi "
         + "JOIN FETCH fsi.flashSale s "
         + "JOIN FETCH fsi.product p "
         + "WHERE fsi.flashSaleItemId = :itemId AND s.flashSaleId = :flashSaleId")
    Optional<FlashSaleItem> findByItemAndSale(@Param("itemId") Long itemId,
                                              @Param("flashSaleId") Long flashSaleId);

    /**
     * [Phase 23-2] CAS 예약 — 조건부 UPDATE 단일 시도.
     *
     * <p>반환값 0 = 재고 부족(sold_out). 1 = 예약 성공.
     * WHERE 조건이 remaining_quantity >= qty 이므로 DB 측 원자성으로
     * 오버셀이 불가능하다. 재시도는 하지 않는다(선착순 공정성 유지).</p>
     */
    @Modifying
    @Query("UPDATE FlashSaleItem f SET f.remainingQuantity = f.remainingQuantity - :qty, "
         + "f.version = f.version + 1 "
         + "WHERE f.flashSaleItemId = :id AND f.remainingQuantity >= :qty")
    int reserveAtomic(@Param("id") Long id, @Param("qty") int qty);

    /**
     * [Phase 23-2] 보상 복구 — UNIQUE 위반 등으로 예약을 되돌려야 할 때 호출한다.
     */
    @Modifying
    @Query("UPDATE FlashSaleItem f SET f.remainingQuantity = f.remainingQuantity + :qty, "
         + "f.version = f.version + 1 "
         + "WHERE f.flashSaleItemId = :id")
    int restoreAtomic(@Param("id") Long id, @Param("qty") int qty);
}
