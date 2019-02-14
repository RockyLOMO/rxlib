package org.rx.fl.service.order;

import org.rx.fl.repository.model.Order;

import java.util.ArrayList;
import java.util.List;

public class NotifyOrdersInfo {
    public final List<Order> paidOrders = new ArrayList<>();
    public final List<Order> settleOrders = new ArrayList<>();
    public final List<Order> restoreSettleOrder = new ArrayList<>();
}
