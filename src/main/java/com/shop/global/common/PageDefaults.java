package com.shop.global.common;

/**
 * 컨트롤러/서비스에서 사용하는 페이지 크기 상수 모음.
 *
 * 매직 넘버(8, 10, 20 등)가 컨트롤러 곳곳에 흩어져 있으면
 * 값 변경 시 모든 사용처를 찾아 수정해야 하고, 의도를 파악하기 어렵다.
 * 이 클래스에 모아두면 한 곳에서 정책을 관리할 수 있다.
 */
public final class PageDefaults {

    private PageDefaults() {}

    // ── 홈 페이지 ───────────────────────────────────
    /** 홈 화면 섹션별(베스트셀러, 신상품, 할인) 표시 개수 */
    public static final int HOME_SECTION_SIZE = 8;

    // ── 관리자 ──────────────────────────────────────
    /** 관리자 대시보드 미리보기 개수 */
    public static final int ADMIN_DASHBOARD_SIZE = 10;
    /** 관리자 목록(주문/상품) 페이지 크기 */
    public static final int ADMIN_LIST_SIZE = 20;

    // ── 사용자 ──────────────────────────────────────
    /** 마이페이지 최근 주문 표시 개수 */
    public static final int MYPAGE_RECENT_ORDERS = 5;
    /** 주문 목록 / 리뷰 목록 / 상품 상세 리뷰 페이지 크기 */
    public static final int DEFAULT_LIST_SIZE = 10;
    /** 위시리스트 / 쿠폰 목록 페이지 크기 */
    public static final int LARGE_LIST_SIZE = 20;
}
