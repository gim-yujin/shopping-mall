package com.shop.domain.product.controller;

import com.shop.domain.category.service.CategoryService;
import com.shop.domain.product.service.ProductService;
import com.shop.domain.search.service.SearchService;
import com.shop.global.common.PageDefaults;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    private final ProductService productService;
    private final CategoryService categoryService;
    private final SearchService searchService;

    public HomeController(ProductService productService, CategoryService categoryService,
                          SearchService searchService) {
        this.productService = productService;
        this.categoryService = categoryService;
        this.searchService = searchService;
    }

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("categories", categoryService.getTopLevelCategories());
        model.addAttribute("bestSellers", productService.getBestSellers(PageRequest.of(0, PageDefaults.HOME_SECTION_SIZE)));
        model.addAttribute("newArrivals", productService.getNewArrivals(PageRequest.of(0, PageDefaults.HOME_SECTION_SIZE)));
        model.addAttribute("deals", productService.getDeals(PageRequest.of(0, PageDefaults.HOME_SECTION_SIZE)));
        model.addAttribute("popularKeywords", searchService.getPopularKeywords());
        return "home";
    }
}
