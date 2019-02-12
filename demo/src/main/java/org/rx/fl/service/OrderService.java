package org.rx.fl.service;

import com.google.common.base.Strings;
import lombok.extern.slf4j.Slf4j;
import org.rx.annotation.ErrorCode;
import org.rx.common.App;
import org.rx.common.NQuery;
import org.rx.common.SystemException;
import org.rx.fl.dto.media.MediaType;
import org.rx.fl.dto.media.OrderInfo;
import org.rx.fl.dto.media.OrderStatus;
import org.rx.fl.dto.repo.*;
import org.rx.fl.repository.OrderMapper;
import org.rx.fl.repository.model.*;
import org.rx.fl.util.DbUtil;
import org.rx.util.NEnum;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.List;

import static org.rx.common.Contract.*;
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

    public List<OrderResult> queryOrders(QueryOrdersParameter parameter) {
        require(parameter, parameter.getUserId());

        OrderExample query = new OrderExample();
        query.setLimit(100);
        OrderExample.Criteria criteria = query.createCriteria().andUserIdEqualTo(parameter.getUserId());
        if (parameter.getOrderNo() != null) {
            criteria.andOrderNoEqualTo(parameter.getOrderNo());
        }
        if (parameter.getStartTime() != null) {
            criteria.andCreateTimeGreaterThanOrEqualTo(parameter.getStartTime());
        }
        if (parameter.getEndTime() != null) {
            criteria.andCreateTimeLessThanOrEqualTo(parameter.getEndTime());
        }
        query.setOrderByClause("create_time desc");
        List<Order> orders = orderMapper.selectByExample(query);
        return NQuery.of(orders).select(p -> {
            OrderResult result = new OrderResult();
            result.setMediaType(NEnum.valueOf(MediaType.class, p.getMediaType()));
            result.setOrderNo(p.getOrderNo());
            result.setGoodsId(p.getGoodsId());
            result.setGoodsName(p.getGoodsName());
            result.setRebateAmount(p.getRebateAmount());
            result.setStatus(NEnum.valueOf(OrderStatus.class, p.getStatus()));
            result.setCreateTime(p.getCreateTime());
            return result;
        }).toList();
    }

    /**
     * do not try catch, exec through trans
     * do not use newComb
     *
     * @param orderInfos
     */
    @Transactional
    public void saveOrders(List<OrderInfo> orderInfos, List<Order> paidOrders, List<Order> settleOrders) {
        require(orderInfos);

        for (OrderInfo media : orderInfos) {
            require(media.getMediaType(), media.getOrderNo(), media.getGoodsId());

            String orderId = App.hash(media.getMediaType().getValue() + media.getOrderNo() + media.getGoodsId()).toString();
            Order order = orderMapper.selectByPrimaryKey(orderId);
            boolean insert = false;
            if (order == null) {
                insert = true;
                order = new Order();
                order.setCreateTime(media.getCreateTime());
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
                    if (media.getStatus() == OrderStatus.Paid && paidOrders != null) {
                        paidOrders.add(order);
                    }
                }
            }
            order.setSettleAmount(toCent(media.getSettleAmount()));

            order.setStatus(media.getStatus().getValue());
            if (!Strings.isNullOrEmpty(order.getUserId()) && media.getStatus() != OrderStatus.Paid) {
                boolean hasSettleOrder = userService.hasSettleOrder(order.getUserId(), order.getId());
                String clientIp = "0.0.0.0";
                Long amount = isNull(order.getSettleAmount(), order.getRebateAmount());
                switch (media.getStatus()) {
                    case Success:
                    case Settlement:
                        if (!hasSettleOrder) {
                            userService.saveUserBalance(order.getUserId(), clientIp, BalanceSourceKind.Order, order.getId(), amount);
                            if (settleOrders != null) {
                                settleOrders.add(order);
                            }
                        }
                        break;
                    case Invalid:
                        if (hasSettleOrder) {
                            userService.saveUserBalance(order.getUserId(), clientIp, BalanceSourceKind.InvalidOrder, order.getId(), amount);
                        }
                        break;
                }
            }

            dbUtil.save(order, insert);
        }
    }

    @ErrorCode(value = "orderNotExist", messageKeys = {"$orderNo"})
    @ErrorCode(value = "mediaUnknown", messageKeys = {"$orderNo"})
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
            throw new SystemException(values(orderNo), "mediaUnknown");
        }

        //等待同步订单时候会自动加钱
        Order order = orders.get(0);
        order.setUserId(userId);
        dbUtil.save(order);

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
