package com.shop.domain.category.controller;

import com.shop.domain.category.entity.Category;
import com.shop.domain.category.service.CategoryService;
import com.shop.domain.product.service.ProductService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@Controller
@RequestMapping("/categories")
public class CategoryController {

    private final CategoryService categoryService;
    private final ProductService productService;

    public CategoryController(CategoryService categoryService, ProductService productService) {
        this.categoryService = categoryService;
        this.productService = productService;
    }

    @GetMapping("/{categoryId}")
    public String categoryProducts(@PathVariable Integer categoryId,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "20") int size,
                                   Model model) {
        Category category = categoryService.findById(categoryId);
        List<Integer> categoryIds = categoryService.getAllDescendantIds(categoryId);
        Pageable pageable = PageRequest.of(page, size);

        model.addAttribute("category", category);
        model.addAttribute("subCategories", categoryService.getSubCategories(categoryId));
        model.addAttribute("products", productService.findByCategoryIds(categoryIds, pageable));
        model.addAttribute("breadcrumb", categoryService.getBreadcrumb(categoryId));
        model.addAttribute("allCategories", categoryService.getTopLevelCategories());
        return "product/list";
    }
}
