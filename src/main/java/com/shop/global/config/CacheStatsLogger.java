package com.shop.global.config;

import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CacheStatsLogger {

    private static final Logger log = LoggerFactory.getLogger(CacheStatsLogger.class);
    private final CacheManager cacheManager;

    public CacheStatsLogger(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    @Scheduled(fixedRate = 30000) // 30초마다 출력
    public void logCacheStats() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n╔══════════════════════════════════════════════════════════════╗\n");
        sb.append("║              CAFFEINE CACHE STATISTICS                       ║\n");
        sb.append("╠══════════════════════════════════════════════════════════════╣\n");
        sb.append(String.format("║ %-18s │ %6s │ %6s │ %8s │ %5s ║\n",
                "Cache Name", "Hits", "Misses", "Hit Rate", "Size"));
        sb.append("╠══════════════════════════════════════════════════════════════╣\n");

        long totalHits = 0, totalMisses = 0;

        for (String cacheName : cacheManager.getCacheNames()) {
            var cache = cacheManager.getCache(cacheName);
            if (cache instanceof CaffeineCache caffeineCache) {
                CacheStats stats = caffeineCache.getNativeCache().stats();
                long hits = stats.hitCount();
                long misses = stats.missCount();
                double hitRate = stats.hitRate() * 100;
                long size = caffeineCache.getNativeCache().estimatedSize();

                totalHits += hits;
                totalMisses += misses;

                sb.append(String.format("║ %-18s │ %6d │ %6d │ %7.1f%% │ %5d ║\n",
                        cacheName, hits, misses, hitRate, size));
            }
        }

        sb.append("╠══════════════════════════════════════════════════════════════╣\n");
        double totalRate = (totalHits + totalMisses) > 0
                ? (double) totalHits / (totalHits + totalMisses) * 100 : 0;
        sb.append(String.format("║ %-18s │ %6d │ %6d │ %7.1f%% │       ║\n",
                "TOTAL", totalHits, totalMisses, totalRate));
        sb.append("╚══════════════════════════════════════════════════════════════╝");

        log.info(sb.toString());
    }
}
