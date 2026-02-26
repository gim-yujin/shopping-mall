package com.shop.domain.search.controller;

import com.shop.domain.product.entity.Product;
import com.shop.domain.product.service.ProductService;
import com.shop.domain.search.service.SearchService;
import com.shop.global.common.PagingParams;
import com.shop.global.security.ClientIpResolver;
import com.shop.global.security.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/search")
public class SearchController {

    private static final int MAX_KEYWORD_LENGTH = 200;

    private final ProductService productService;
    private final SearchService searchService;
    private final ClientIpResolver clientIpResolver;

    public SearchController(ProductService productService, SearchService searchService, ClientIpResolver clientIpResolver) {
        this.productService = productService;
        this.searchService = searchService;
        this.clientIpResolver = clientIpResolver;
    }

    @GetMapping
    public String search(@RequestParam(name = "q", required = false, defaultValue = "") String keyword,
                         @RequestParam(defaultValue = "0") int page,
                         @RequestParam(defaultValue = "20") int size,
                         HttpServletRequest request,
                         Model model) {
        keyword = (keyword == null) ? "" : keyword.trim();
        if (keyword.length() > MAX_KEYWORD_LENGTH) {
            keyword = keyword.substring(0, MAX_KEYWORD_LENGTH);
            model.addAttribute("keywordValidationMessage", "검색어는 최대 200자까지 입력할 수 있어 앞부분만 검색했어요.");
        }
        model.addAttribute("keyword", keyword);

        if (keyword.isEmpty()) {
            model.addAttribute("popularKeywords", searchService.getPopularKeywords());
            return "product/search";
        }

        int normalizedPage = PagingParams.normalizePage(page);
        int normalizedSize = PagingParams.normalizeSize(size);

        Page<Product> results = productService.search(keyword, PageRequest.of(normalizedPage, normalizedSize));
        model.addAttribute("products", results);

        // 첫 페이지에서만 검색 로그 기록 (페이지네이션 시 중복 기록 방지)
        if (normalizedPage == 0) {
            Long userId = SecurityUtil.getCurrentUserId().orElse(null);
            String clientIp = clientIpResolver.resolveClientIp(request);
            searchService.logSearch(userId, keyword, (int) results.getTotalElements(),
                    clientIp, request.getHeader("User-Agent"));
        }

        return "product/search";
    }
}
