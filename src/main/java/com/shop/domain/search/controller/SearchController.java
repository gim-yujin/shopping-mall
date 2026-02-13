package com.shop.domain.search.controller;

import com.shop.domain.product.entity.Product;
import com.shop.domain.product.service.ProductService;
import com.shop.domain.search.service.SearchService;
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

    private final ProductService productService;
    private final SearchService searchService;

    public SearchController(ProductService productService, SearchService searchService) {
        this.productService = productService;
        this.searchService = searchService;
    }

    @GetMapping
    public String search(@RequestParam(name = "q", required = false, defaultValue = "") String keyword,
                         @RequestParam(defaultValue = "0") int page,
                         @RequestParam(defaultValue = "20") int size,
                         HttpServletRequest request,
                         Model model) {
        keyword = (keyword == null) ? "" : keyword.trim();
        model.addAttribute("keyword", keyword);

        if (keyword.isEmpty()) {
            model.addAttribute("popularKeywords", searchService.getPopularKeywords());
            return "product/search";
        }

        Page<Product> results = productService.search(keyword, PageRequest.of(page, size));
        model.addAttribute("products", results);

        Long userId = SecurityUtil.getCurrentUserId().orElse(null);
        searchService.logSearch(userId, keyword, (int) results.getTotalElements(),
                request.getRemoteAddr(), request.getHeader("User-Agent"));

        return "product/search";
    }
}
