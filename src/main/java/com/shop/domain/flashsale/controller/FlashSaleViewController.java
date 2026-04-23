package com.shop.domain.flashsale.controller;

import com.shop.domain.flashsale.service.FlashSaleQueryService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.LocalDateTime;

@Controller
@RequestMapping("/flash-sales")
public class FlashSaleViewController {

    private final FlashSaleQueryService flashSaleQueryService;

    public FlashSaleViewController(FlashSaleQueryService flashSaleQueryService) {
        this.flashSaleQueryService = flashSaleQueryService;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("flashSales", flashSaleQueryService.listActiveAndUpcoming());
        model.addAttribute("now", LocalDateTime.now());
        return "flashsale/list";
    }

    @GetMapping("/{flashSaleId}")
    public String detail(@PathVariable Long flashSaleId, Model model) {
        model.addAttribute("flashSale", flashSaleQueryService.getDetail(flashSaleId));
        model.addAttribute("now", LocalDateTime.now());
        return "flashsale/detail";
    }
}
