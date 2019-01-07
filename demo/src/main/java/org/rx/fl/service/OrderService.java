package org.rx.fl.service;

import lombok.extern.slf4j.Slf4j;
import org.rx.App;
import org.rx.ErrorCode;
import org.rx.InvalidOperationException;
import org.rx.SystemException;
import org.rx.bean.DateTime;
import org.rx.fl.model.MediaType;
import org.rx.fl.model.OrderInfo;
import org.rx.fl.repository.OrderMapper;
import org.rx.fl.repository.UserGoodsMapper;
import org.rx.fl.repository.model.Order;
import org.rx.fl.repository.model.OrderExample;
import org.rx.fl.repository.model.UserGoods;
import org.rx.fl.repository.model.UserGoodsExample;
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
    private UserGoodsMapper userGoodsMapper;
    @Resource
    private DbUtil dbUtil;

    @Transactional
    public void saveOrders(MediaType mediaType, List<OrderInfo> orderInfos) {
        require(mediaType, orderInfos);

        for (OrderInfo media : orderInfos) {
            try {
                String orderId = App.newComb(mediaType.ordinal() + media.getOrderNo(), media.getCreateTime()).toString();
                Order order = orderMapper.selectByPrimaryKey(orderId);
                boolean insert = false;
                if (order == null) {
                    insert = true;
                    order = new Order();

                    UserGoodsExample q = new UserGoodsExample();
                    q.setLimit(2);
                    q.createCriteria().andMediaTypeEqualTo(mediaType.ordinal())
                            .andGoodsIdEqualTo(media.getGoodsId())
                            .andCreateTimeGreaterThanOrEqualTo(DateTime.now().addDays(-1))
                            .andIsDeletedEqualTo(DbUtil.IsDeleted_False);
                    List<UserGoods> userGoodsList = userGoodsMapper.selectByExample(q);
                    if (userGoodsList.size() == 1) {
                        UserGoods userGoods = userGoodsList.get(0);

                        UserGoods toUpdate = new UserGoods();
                        toUpdate.setId(userGoods.getId());
                        toUpdate.setIsDeleted(DbUtil.IsDeleted_True);
                        userGoodsMapper.updateByPrimaryKeySelective(toUpdate);

                        order.setUserId(userGoods.getUserId());
                    }

                    order.setMediaType(mediaType.ordinal());
                    order.setOrderNo(media.getOrderNo());
                    order.setGoodsId(media.getGoodsId());
                    order.setGoodsName(media.getGoodsName());
                    order.setUnitPrice(toCent(media.getUnitPrice()));
                    order.setQuantity(media.getQuantity());
                    order.setSellerName(media.getSellerName());
                    order.setPayAmount(toCent(media.getPayAmount()));
                    order.setRebateAmount(toCent(media.getRebateAmount()));
                }
                order.setStatus(media.getStatus().getValue());
                dbUtil.save(order, insert);
            } catch (Exception e) {
                log.error("save", e);
            }
        }
    }

    @ErrorCode(value = "orderNotExist", messageKeys = {"$orderNo"})
    public void rebindOrder(String userId, String orderNo) {
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
    }
}
