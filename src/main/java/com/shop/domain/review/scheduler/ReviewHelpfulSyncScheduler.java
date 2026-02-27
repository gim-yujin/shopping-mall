package com.shop.domain.review.scheduler;

import com.shop.domain.review.repository.ReviewRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * [BUG FIX] 리뷰 helpful_count 야간 동기화 스케줄러.
 *
 * 문제: ReviewService.toggleHelpful()에서 "도움이 돼요" 토글 시
 * incrementHelpfulCount / decrementHelpfulCount로 helpful_count를 실시간 갱신한다.
 * 그러나 동시 토글 시 다음 시나리오에서 count가 실제 레코드 수와 불일치할 수 있다:
 *
 *   Thread A: DELETE review_helpful (성공)
 *   Thread B: INSERT review_helpful (성공, A의 DELETE 후 UNIQUE 슬롯 확보)
 *   Thread A: decrementHelpfulCount (helpful_count = N-1)
 *   Thread B: incrementHelpfulCount (helpful_count = N)
 *   → 실제 레코드 수는 N이지만, A의 decrement와 B의 increment가 교차하면서
 *     특정 타이밍에 count 오차가 누적될 수 있다.
 *
 * 기존 ReviewRepository에 syncHelpfulCount() 메서드가 구현되어 있었으나,
 * 이를 주기적으로 호출하는 스케줄러가 없어 누적 오차가 보정되지 않았다.
 *
 * 해결: 매일 새벽 2시에 전체 리뷰의 helpful_count를 실제 review_helpfuls 레코드 수로
 * 일괄 동기화한다. 두 가지 쿼리를 순차 실행한다:
 *   1) syncAllHelpfulCounts(): review_helpfuls에 레코드가 있는 리뷰의 count 보정
 *   2) resetOrphanedHelpfulCounts(): review_helpfuls가 0건인데 count > 0인 리뷰 보정
 */
@Component
public class ReviewHelpfulSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReviewHelpfulSyncScheduler.class);

    private final ReviewRepository reviewRepository;

    public ReviewHelpfulSyncScheduler(ReviewRepository reviewRepository) {
        this.reviewRepository = reviewRepository;
    }

    /**
     * 매일 새벽 2시에 helpful_count를 실제 review_helpfuls 레코드 수로 일괄 동기화한다.
     *
     * 두 단계로 나누어 처리하는 이유:
     *   - syncAllHelpfulCounts(): review_helpfuls GROUP BY 결과와 helpful_count를 비교하여 보정.
     *     다만 review_helpfuls에 레코드가 하나도 없는 리뷰는 GROUP BY 결과에 나타나지 않으므로
     *     이 쿼리만으로는 "모든 helpful을 취소했지만 count가 양수로 남은 경우"를 잡지 못한다.
     *   - resetOrphanedHelpfulCounts(): 위 누락 케이스를 NOT EXISTS 서브쿼리로 별도 처리.
     */
    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void syncHelpfulCounts() {
        long startTime = System.nanoTime();

        try {
            int synced = reviewRepository.syncAllHelpfulCounts();
            int orphaned = reviewRepository.resetOrphanedHelpfulCounts();

            long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
            log.info("리뷰 helpful_count 동기화 완료 - synced={}, orphanedReset={}, elapsedMs={}",
                    synced, orphaned, elapsedMs);
        } catch (Exception e) {
            log.error("리뷰 helpful_count 동기화 실패", e);
        }
    }
}
