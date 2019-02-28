package org.rx.fl.service.order;

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
import org.rx.fl.repository.CommissionMapper;
import org.rx.fl.repository.OrderMapper;
import org.rx.fl.repository.model.*;
import org.rx.fl.service.user.UserNode;
import org.rx.fl.service.user.UserService;
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
    private CommissionMapper commissionMapper;
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
    public void saveOrders(List<OrderInfo> orderInfos, NotifyOrdersInfo notify) {
        require(orderInfos);
        if (notify == null) {
            notify = new NotifyOrdersInfo();
        }

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

                String userId = userService.findUserByGoods(media.getMediaType(), media.getGoodsId(), media.getPromotionId());
                if (!Strings.isNullOrEmpty(userId)) {
                    order.setUserId(userId);
                }
            }
            order.setSettleAmount(toCent(media.getSettleAmount()));

            order.setStatus(media.getStatus().getValue());
            if (!Strings.isNullOrEmpty(order.getUserId())) {
                computeRebate(order);
                Commission commission = computeCommission(order);
                Long amount = DbUtil.getRebateAmount(order.getRebateAmount(), order.getSettleAmount());
                switch (media.getStatus()) {
                    case Paid:
                        if (insert) {
                            notify.paidOrders.add(order);
                            if (commission != null) {
                                notify.paidCommissionOrders.add(commission);
                            }
                        }
                        break;
                    case Success:
                    case Settlement:
                        if (userService.trySettleOrder(order.getUserId(), order.getId(), amount, commission)) {
                            notify.settleOrders.add(order);
                            if (commission != null) {
                                notify.settleCommissionOrders.add(commission);
                            }
                        }
                        break;
                    case Invalid:
                        if (userService.tryRestoreSettleOrder(order.getUserId(), order.getId(), amount, commission)) {
                            notify.restoreSettleOrder.add(order);
                        }
                        break;
                }
            }

            dbUtil.save(order, insert);
        }
    }

    //region Commission
    private Commission computeCommission(Order order) {
        UserNode parentRelation = userService.getParentRelation(order.getUserId());
        if (parentRelation == null) {
            return null;
        }

        String id = App.hash(parentRelation.getId() + order.getUserId() + order.getId()).toString();
        Commission commission = commissionMapper.selectByPrimaryKey(id);
        boolean insert = false;
        if (commission == null) {
            insert = true;
            commission = new Commission();
            commission.setId(id);
            commission.setUserId(parentRelation.getId());
            commission.setChildUserId(order.getUserId());
            commission.setOrderId(order.getId());
        }

        long rebateAmount = DbUtil.getRebateAmount(order.getRawRebateAmount(), order.getRawSettleAmount());
        MediaType mediaType = NEnum.valueOf(MediaType.class, order.getMediaType());
        long amount = userService.compute(commission.getUserId(), mediaType, rebateAmount) - order.getRebateAmount();
        if (amount <= 0) {
            return null;
        }
        commission.setAmount(amount);
        return dbUtil.save(commission, insert);
    }

//    public Commission getCommission(String orderId) {
//        require(orderId);
//        Order order = orderMapper.selectByPrimaryKey(orderId);
//        if (order == null) {
//            throw new InvalidOperationException("order not found");
//        }
//        UserNode parentPartner = userService.getParentRelation(order.getUserId());
//        if (parentPartner == null) {
//            return null;
//        }
//
//        String id = App.hash(parentPartner.getId() + order.getUserId() + order.getId()).toString();
//        return commissionMapper.selectByPrimaryKey(id);
//    }
    //endregion

    //处理用户返利比例,少于1分给1分钱
    public void computeRebate(Order order) {
        require(order, order.getUserId(), order.getMediaType());

        MediaType mediaType = NEnum.valueOf(MediaType.class, order.getMediaType());
        if (order.getRawRebateAmount() == null) {
            long rebateAmount = order.getRebateAmount();
            order.setRawRebateAmount(rebateAmount);
            order.setRebateAmount(Math.max(1, userService.compute(order.getUserId(), mediaType, rebateAmount)));
        }
        if (order.getRawSettleAmount() == null && !DbUtil.isEmpty(order.getSettleAmount())) {
            long settleAmount = order.getSettleAmount();
            order.setRawSettleAmount(settleAmount);
            //取结算金额中最小的
            order.setSettleAmount(Math.max(1, Math.min(order.getRebateAmount(), userService.compute(order.getUserId(), mediaType, settleAmount))));
        }
    }

    @ErrorCode(value = "orderNotExist", messageKeys = {"$orderNo"})
    @ErrorCode(value = "mediaUnknown", messageKeys = {"$orderNo"})
    @ErrorCode(value = "alreadyBind", messageKeys = {"$orderNo"})
    @Transactional
    public RebindOrderResult rebindOrder(String userId, String orderNo) {
        require(userId, orderNo);

        OrderExample q = new OrderExample();
        q.createCriteria().andOrderNoEqualTo(orderNo);
        List<Order> orders = orderMapper.selectByExample(q);
        if (orders.isEmpty()) {
            throw new SystemException(values(orderNo), "orderNotExist");
        }
        if (orders.size() > 1) {
            throw new SystemException(values(orderNo), "mediaUnknown");
        }
        Order order = orders.get(0);
        if (!Strings.isNullOrEmpty(order.getUserId()) && !userId.equals(order.getUserId())) {
            throw new SystemException(values(orderNo), "alreadyBind");
        }

        //等待同步订单时候会自动加钱
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
