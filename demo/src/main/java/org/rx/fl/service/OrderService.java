package org.rx.fl.service;

import com.google.common.base.Strings;
import lombok.extern.slf4j.Slf4j;
import org.rx.annotation.ErrorCode;
import org.rx.beans.DateTime;
import org.rx.common.App;
import org.rx.common.InvalidOperationException;
import org.rx.common.NQuery;
import org.rx.common.SystemException;
import org.rx.fl.dto.media.MediaType;
import org.rx.fl.dto.media.OrderInfo;
import org.rx.fl.dto.media.OrderStatus;
import org.rx.fl.dto.repo.OrderResult;
import org.rx.fl.repository.OrderMapper;
import org.rx.fl.repository.model.*;
import org.rx.fl.dto.repo.BalanceSourceKind;
import org.rx.fl.dto.repo.RebindOrderResult;
import org.rx.fl.dto.repo.UserDto;
import org.rx.fl.util.DbUtil;
import org.rx.util.NEnum;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.List;

import static org.rx.common.Contract.require;
import static org.rx.common.Contract.values;
import static org.rx.fl.util.DbUtil.toCent;
import static org.rx.util.AsyncTask.TaskFactory;

@Service
@Slf4j
public class OrderService {
    @Resource
    private OrderMapper orderMapper;
    @Resource
    private UserService userService;
    @Resource
    private MediaService mediaService;
    @Resource
    private DbUtil dbUtil;

    public OrderService() {
//        TaskFactory.schedule(() -> {
//            for (MediaType media : mediaService.getMedias()) {
//                try {
//                    DateTime now = DateTime.now();
//                    DateTime start = now.addDays(-1);
//                    saveOrders(mediaService.findOrders(media, start, now));
//                } catch (Exception e) {
//                    log.error("saveOrders", e);
//                }
//            }
//        }, 120 * 1000);
    }

    public List<OrderResult> queryOrders(String userId, int takeCount) {
        require(userId);
        require(takeCount, takeCount > 0);

        OrderExample query = new OrderExample();
        query.setLimit(takeCount);
        query.createCriteria().andUserIdEqualTo(userId);
        query.setOrderByClause("create_time desc");
        List<Order> orders = orderMapper.selectByExample(query);
        return NQuery.of(orders).select(p -> {
            OrderResult result = new OrderResult();
            result.setOrderNo(p.getOrderNo());
            result.setGoodsName(p.getGoodsName());
            result.setRebateAmount(p.getRebateAmount());
            result.setStatus(NEnum.valueOf(OrderStatus.class, p.getStatus()));
            result.setCreateTime(p.getCreateTime());
            return result;
        }).toList();
    }

    @Transactional
    public void saveOrders(List<OrderInfo> orderInfos) {
        require(orderInfos);

        for (OrderInfo media : orderInfos) {
            require(media.getOrderNo(), media.getCreateTime());

            //do not try catch, exec through trans
            String orderId = App.newComb(media.getMediaType().getValue() + media.getOrderNo(), media.getCreateTime()).toString();
            Order order = orderMapper.selectByPrimaryKey(orderId);
            boolean insert = false;
            if (order == null) {
                insert = true;
                order = new Order();
                order.setId(orderId);
                order.setMediaType(media.getMediaType().getValue());
                order.setOrderNo(media.getOrderNo());
                order.setGoodsId(media.getGoodsId());
                order.setGoodsName(media.getGoodsName());
                order.setUnitPrice(toCent(media.getUnitPrice()));
                order.setQuantity(media.getQuantity());
                order.setSellerName(media.getSellerName());
                order.setPayAmount(toCent(media.getPayAmount()));
                order.setRebateAmount(toCent(media.getRebateAmount()));

                String userId = userService.findUserByGoods(media.getMediaType(), media.getGoodsId());
                if (!Strings.isNullOrEmpty(userId)) {
                    order.setUserId(userId);
                }
            }
            order.setStatus(media.getStatus().getValue());
            dbUtil.save(order, insert);

            if (!Strings.isNullOrEmpty(order.getUserId())
                    && NQuery.of(OrderStatus.Success.getValue(), OrderStatus.Settlement.getValue()).contains(order.getStatus())
                    && !userService.hasSettleOrder(order.getUserId(), order.getId())) {
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
        UserDto user = userService.queryUser(userId);

        RebindOrderResult result = new RebindOrderResult();
        result.setOrderNo(order.getOrderNo());
        result.setPayAmount(order.getPayAmount());
        result.setRebateAmount(order.getRebateAmount());
        result.setBalance(user.getBalance());
        result.setUnconfirmedOrderAmount(user.getUnconfirmedOrderAmount());
        return result;
    }
}
