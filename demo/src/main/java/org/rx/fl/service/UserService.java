package org.rx.fl.service;

import org.rx.App;
import org.rx.InvalidOperationException;
import org.rx.fl.repository.BalanceLogMapper;
import org.rx.fl.repository.CheckInLogMapper;
import org.rx.fl.repository.OrderMapper;
import org.rx.fl.repository.UserMapper;
import org.rx.fl.repository.model.*;
import org.rx.fl.service.dto.BalanceSourceKind;
import org.rx.fl.service.dto.BalanceType;
import org.rx.fl.service.dto.OrderStatus;
import org.rx.fl.service.dto.UserInfo;
import org.rx.fl.util.DbUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;

import static org.rx.Contract.require;

@Service
public class UserService {
    @Resource
    private UserMapper userMapper;
    @Resource
    private BalanceLogMapper balanceLogMapper;
    @Resource
    private CheckInLogMapper checkInLogMapper;
    @Resource
    private OrderMapper orderMapper;
    @Resource
    private DbUtil dbUtil;

    public UserInfo queryUser(String userId) {
        require(userId);

        User user = dbUtil.selectById(userMapper, userId);

        UserInfo userInfo = new UserInfo();
        userInfo.setUserId(user.getId());
        userInfo.setBalance(user.getBalance());
        userInfo.setFreezeAmount(user.getFreezeAmount());

        BalanceLogExample qBalance = new BalanceLogExample();
        qBalance.createCriteria().andUserIdEqualTo(user.getId())
                .andSourceEqualTo(BalanceSourceKind.Withdraw.getValue());
        userInfo.setTotalWithdrawAmount(balanceLogMapper.sumAmount(qBalance));
        qBalance = new BalanceLogExample();
        qBalance.createCriteria().andUserIdEqualTo(user.getId())
                .andSourceEqualTo(BalanceSourceKind.Withdrawing.getValue());
        userInfo.setWithdrawingAmount(balanceLogMapper.sumAmount(qBalance));

        OrderExample qOrder = new OrderExample();
        qOrder.createCriteria().andUserIdEqualTo(user.getId())
                .andStatusEqualTo(OrderStatus.Paid.getValue());
        userInfo.setUnconfirmedOrderAmount(orderMapper.sumRebateAmount(qOrder));
        qOrder = new OrderExample();
        qOrder.createCriteria().andUserIdEqualTo(user.getId())
                .andStatusIn(Arrays.asList(OrderStatus.Success.getValue(), OrderStatus.Settlement.getValue()));
        userInfo.setConfirmedOrderCount(orderMapper.countByExample(qOrder));

        CheckInLogExample query = new CheckInLogExample();
        query.createCriteria().andUserIdEqualTo(user.getId());
        userInfo.setCheckInCount(checkInLogMapper.countByExample(query));
        userInfo.setCheckInAmount(checkInLogMapper.sumBonus(query));
        return userInfo;
    }

    @Transactional
    public void bindPayment(String userId, String aliPayName, String aliPayAccount) {
        require(userId, aliPayName, aliPayAccount);

        User user = new User();
        user.setAlipayName(aliPayName);
        user.setAlipayAccount(aliPayAccount);
        UserExample query = new UserExample();
        query.createCriteria().andIdEqualTo(userId);
        int r = userMapper.updateByExampleSelective(user, query);
        if (r != 1) {
            throw new InvalidOperationException(String.format("User %s bind payment fail", userId));
        }
    }

    @Transactional
    public long checkIn(String userId, String clientIp) {
        require(userId, clientIp);

        long bonus = ThreadLocalRandom.current().nextLong(1, 10);

        CheckInLog log = new CheckInLog();
        log.setUserId(userId);
        log.setBonus(bonus);
        log.setClientIp(clientIp);
        dbUtil.save(log);

        App.retry(p -> {
            User user = dbUtil.selectById(userMapper, userId);

            BalanceLog balanceLog = new BalanceLog();
            balanceLog.setUserId(user.getId());
            balanceLog.setType(BalanceType.Income.getValue());
            balanceLog.setSource(BalanceSourceKind.CheckIn.getValue());
            balanceLog.setSourceId(log.getId());

            balanceLog.setPreBalance(user.getBalance());
            user.setBalance(user.getBalance() + bonus);
            balanceLog.setPostBalance(user.getBalance());
            balanceLog.setValue(bonus);

            balanceLog.setClientIp(clientIp);
            balanceLog.setVersion(1L);
            dbUtil.save(balanceLog);

            UserExample selective = new UserExample();
            selective.createCriteria()
                    .andIdEqualTo(user.getId())
                    .andVersionEqualTo(user.getVersion());
            if (user.getVersion() == null) {
                user.setVersion(1L);
            } else {
                user.setVersion(user.getVersion() + 1);
            }
            int rowsCount = userMapper.updateByExampleSelective(user, selective);
            if (rowsCount != 1) {
                throw new InvalidOperationException("Concurrent update fail");
            }
            return null;
        }, null, 2);

        return bonus;
    }
}
