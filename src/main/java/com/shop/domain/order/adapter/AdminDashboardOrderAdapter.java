package com.shop.domain.order.adapter;

import com.shop.domain.order.dto.OrderListReadModel;
import com.shop.domain.order.service.OrderService;
import com.shop.domain.product.port.AdminDashboardOrderPort;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

@Component
public class AdminDashboardOrderAdapter implements AdminDashboardOrderPort {

    private final OrderService orderService;

    public AdminDashboardOrderAdapter(OrderService orderService) {
        this.orderService = orderService;
    }

    @Override
    public Page<OrderListReadModel> getAllOrdersFlat(Pageable pageable) {
        return orderService.getAllOrdersFlat(pageable);
    }

    @Override
    public long getPendingReturnCount() {
        return orderService.getPendingReturnCount();
    }
}
