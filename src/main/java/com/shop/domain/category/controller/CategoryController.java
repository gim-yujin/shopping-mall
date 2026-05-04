package com.shop.domain.category.controller;

import com.shop.domain.category.entity.Category;
import com.shop.domain.category.service.CategoryService;
import com.shop.domain.product.service.ProductQueryService;
import com.shop.global.common.PagingParams;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import java.util.List;

/**
 * [Phase 18] ProductService → ProductQueryService 전환.
 * 카테고리별 상품 목록은 읽기 전용이므로 CQRS Query 서비스를 사용한다.
 */
@Controller
@RequestMapping("/categories")
public class CategoryController {

    private final CategoryService categoryService;
    private final ProductQueryService productQueryService;

    public CategoryController(CategoryService categoryService, ProductQueryService productQueryService) {
        this.categoryService = categoryService;
        this.productQueryService = productQueryService;
    }

    @GetMapping("/{categoryId}")
    public String categoryProducts(@PathVariable Integer categoryId,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "20") int size,
                                   @RequestParam(defaultValue = "best") String sort,
                                   Model model) {
        Category category = categoryService.findById(categoryId);
        List<Integer> categoryIds = categoryService.getAllDescendantIds(categoryId);

        model.addAttribute("category", category);
        model.addAttribute("subCategories", categoryService.getSubCategories(categoryId));
        int normalizedPage = PagingParams.normalizePage(page);
        int normalizedSize = PagingParams.normalizeSize(size);
        String normalizedSort = PagingParams.normalizeProductSort(sort);

        model.addAttribute("products", productQueryService.findByCategoryIdsSorted(categoryIds, normalizedPage, normalizedSize, normalizedSort));
        model.addAttribute("breadcrumb", categoryService.getBreadcrumb(categoryId));
        model.addAttribute("allCategories", categoryService.getTopLevelCategories());
        model.addAttribute("currentSort", normalizedSort);
        model.addAttribute("baseUrl", "/categories/" + categoryId);
        return "product/list";
    }
}
