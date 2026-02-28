package com.shop.domain.point.controller;

import com.shop.domain.point.service.PointQueryService;
import com.shop.global.common.PageDefaults;
import com.shop.global.common.PagingParams;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;

@Controller
@RequestMapping("/admin/points")
public class AdminPointController {

    private final PointQueryService pointQueryService;

    public AdminPointController(PointQueryService pointQueryService) {
        this.pointQueryService = pointQueryService;
    }

    @GetMapping
    public String pointHistories(@RequestParam(defaultValue = "0") int page,
                                 @RequestParam(required = false) LocalDate fromDate,
                                 @RequestParam(required = false) LocalDate toDate,
                                 @RequestParam(required = false) String changeType,
                                 Model model) {
        int normalizedPage = PagingParams.normalizePage(page);
        model.addAttribute("pointHistories", pointQueryService.getPointHistoriesForOps(
                fromDate,
                toDate,
                changeType,
                PageRequest.of(normalizedPage, PageDefaults.ADMIN_LIST_SIZE)
        ));
        model.addAttribute("fromDate", fromDate);
        model.addAttribute("toDate", toDate);
        model.addAttribute("currentChangeType", changeType);
        return "admin/points";
    }
}
