package com.shop.domain.search.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "search_logs")
public class SearchLog {

    private static final int MAX_SEARCH_KEYWORD_LENGTH = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "log_id")
    private Long logId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "search_keyword", nullable = false, length = 200)
    private String searchKeyword;

    @Column(name = "result_count", nullable = false)
    private Integer resultCount;

    @Column(name = "clicked_product_id")
    private Long clickedProductId;

    @Column(name = "searched_at", nullable = false)
    private LocalDateTime searchedAt;

    @Column(name = "ip_address", columnDefinition = "inet")
    private String ipAddress;

    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;

    protected SearchLog() {}

    public SearchLog(Long userId, String searchKeyword, int resultCount, String ipAddress, String userAgent) {
        this.userId = userId;
        this.searchKeyword = normalizeSearchKeyword(searchKeyword);
        this.resultCount = resultCount;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.searchedAt = LocalDateTime.now();
    }


    private String normalizeSearchKeyword(String keyword) {
        if (keyword == null) {
            return "";
        }

        String trimmedKeyword = keyword.trim();
        if (trimmedKeyword.length() <= MAX_SEARCH_KEYWORD_LENGTH) {
            return trimmedKeyword;
        }

        return trimmedKeyword.substring(0, MAX_SEARCH_KEYWORD_LENGTH);
    }

    public Long getLogId() { return logId; }
    public Long getUserId() { return userId; }
    public String getSearchKeyword() { return searchKeyword; }
    public Integer getResultCount() { return resultCount; }
    public Long getClickedProductId() { return clickedProductId; }
    public LocalDateTime getSearchedAt() { return searchedAt; }
}
