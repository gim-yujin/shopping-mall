package com.shop.domain.product.controller;

import com.shop.domain.category.service.CategoryService;
import com.shop.domain.product.service.ProductQueryService;
import com.shop.domain.search.service.SearchService;
import com.shop.global.common.PageDefaults;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * [Phase 18] ProductService → ProductQueryService 전환.
 * 홈 페이지의 읽기 경로를 CQRS Query 서비스로 분리.
 */
@Controller
public class HomeController {

    private final ProductQueryService productQueryService;
    private final CategoryService categoryService;
    private final SearchService searchService;

    public HomeController(ProductQueryService productQueryService, CategoryService categoryService,
                          SearchService searchService) {
        this.productQueryService = productQueryService;
        this.categoryService = categoryService;
        this.searchService = searchService;
    }

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("categories", categoryService.getTopLevelCategories());
        model.addAttribute("bestSellers", productQueryService.getBestSellers(PageRequest.of(0, PageDefaults.HOME_SECTION_SIZE)));
        model.addAttribute("newArrivals", productQueryService.getNewArrivals(PageRequest.of(0, PageDefaults.HOME_SECTION_SIZE)));
        model.addAttribute("deals", productQueryService.getDeals(PageRequest.of(0, PageDefaults.HOME_SECTION_SIZE)));
        model.addAttribute("popularKeywords", searchService.getPopularKeywords());
        return "home";
    }
}
