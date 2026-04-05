package com.shop.domain.order.adapter;

import com.shop.domain.order.dto.OrderListReadModel;
import com.shop.domain.order.service.OrderService;
import com.shop.domain.user.port.MyPageOrderPort;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

@Component
public class MyPageOrderAdapter implements MyPageOrderPort {

    private final OrderService orderService;

    public MyPageOrderAdapter(OrderService orderService) {
        this.orderService = orderService;
    }

    @Override
    public Page<OrderListReadModel> getOrdersByUserFlat(Long userId, Pageable pageable) {
        return orderService.getOrdersByUserFlat(userId, pageable);
    }
}
