package org.rx.fl.service;

import com.google.common.base.Strings;
import lombok.extern.slf4j.Slf4j;
import org.rx.*;
import org.rx.fl.model.MediaType;
import org.rx.fl.model.OrderInfo;
import org.rx.fl.model.OrderStatus;
import org.rx.fl.repository.OrderMapper;
import org.rx.fl.repository.model.*;
import org.rx.fl.service.dto.BalanceSourceKind;
import org.rx.fl.service.dto.RebindOrderResult;
import org.rx.fl.service.dto.UserInfo;
import org.rx.fl.util.DbUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.List;

import static org.rx.Contract.require;
import static org.rx.Contract.values;
import static org.rx.fl.util.DbUtil.toCent;

@Service
@Slf4j
public class OrderService {
    @Resource
    private OrderMapper orderMapper;
    @Resource
    private UserService userService;
    @Resource
    private DbUtil dbUtil;

    @Transactional
    public void saveOrders(MediaType mediaType, List<OrderInfo> orderInfos) {
        require(mediaType, orderInfos);

        for (OrderInfo media : orderInfos) {
            require(media.getOrderNo(), media.getCreateTime());

            //do not try catch, exec through trans
            String orderId = App.newComb(mediaType.ordinal() + media.getOrderNo(), media.getCreateTime()).toString();
            Order order = orderMapper.selectByPrimaryKey(orderId);
            boolean insert = false;
            if (order == null) {
                insert = true;
                order = new Order();
                order.setId(orderId);
                order.setMediaType(mediaType.ordinal());
                order.setOrderNo(media.getOrderNo());
                order.setGoodsId(media.getGoodsId());
                order.setGoodsName(media.getGoodsName());
                order.setUnitPrice(toCent(media.getUnitPrice()));
                order.setQuantity(media.getQuantity());
                order.setSellerName(media.getSellerName());
                order.setPayAmount(toCent(media.getPayAmount()));
                order.setRebateAmount(toCent(media.getRebateAmount()));

                String userId = userService.findUserByGoods(mediaType, media.getGoodsId());
                if (!Strings.isNullOrEmpty(userId)) {
                    order.setUserId(userId);
                }
            }
            order.setStatus(media.getStatus().getValue());
            dbUtil.save(order, insert);

            if (!Strings.isNullOrEmpty(order.getUserId())
                    && NQuery.of(OrderStatus.Success.getValue(), OrderStatus.Settlement.getValue()).contains(order.getStatus())) {
//todo check

                userService.saveUserBalance(order.getUserId(), "0.0.0.0", BalanceSourceKind.Order, order.getId(), order.getRebateAmount());

            }
        }
    }

    @ErrorCode(value = "orderNotExist", messageKeys = {"$orderNo"})
    @Transactional
    public RebindOrderResult rebindOrder(String userId, String orderNo) {
        require(userId, orderNo);

        OrderExample q = new OrderExample();
        q.createCriteria().andUserIdIsNull()
                .andOrderNoEqualTo(orderNo);
        List<Order> orders = orderMapper.selectByExample(q);
        if (orders.isEmpty()) {
            throw new SystemException(values(orderNo), "orderNotExist");
        }
        if (orders.size() > 1) {
            throw new InvalidOperationException(String.format("OrderNo %s have more then one orders", orderNo));
        }

        Order order = orders.get(0);
        order.setUserId(userId);
        dbUtil.save(order);

        userService.saveUserBalance(userId, "0.0.0.0", BalanceSourceKind.RebindOrder, order.getId(), order.getRebateAmount());
        UserInfo user = userService.queryUser(userId);

        RebindOrderResult result = new RebindOrderResult();
        result.setOrderNo(order.getOrderNo());
        result.setPayAmount(order.getPayAmount());
        result.setRebateAmount(order.getRebateAmount());
        result.setBalance(user.getBalance());
        result.setUnconfirmedOrderAmount(user.getUnconfirmedOrderAmount());
        return result;
    }
}
