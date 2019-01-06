package org.rx.fl.service;

import org.rx.App;
import org.rx.InvalidOperationException;
import org.rx.fl.repository.CheckInLogMapper;
import org.rx.fl.repository.UserMapper;
import org.rx.fl.repository.model.*;
import org.rx.fl.service.dto.BalanceSourceKind;
import org.rx.fl.service.dto.BalanceType;
import org.rx.fl.service.dto.UserInfo;
import org.rx.fl.util.DbUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class UserService {
    @Resource
    private UserMapper userMapper;
    @Resource
    private CheckInLogMapper checkInLogMapper;
    @Resource
    private DbUtil dbUtil;

    public UserInfo queryUser(String userId) {
        User user = dbUtil.selectByPrimaryKey(userMapper, userId);

        UserInfo userInfo = new UserInfo();
        userInfo.setUserId(user.getId());
        userInfo.setBalance(user.getBalance());

        CheckInLogExample query = new CheckInLogExample();
        query.createCriteria().andUserIdEqualTo(user.getId());
        userInfo.setTotalCheckInCount(checkInLogMapper.countByExample(query));
        userInfo.setTotalCheckInAmount(checkInLogMapper.sumBonus(query));
        return userInfo;
    }

    @Transactional
    public long checkIn(String userId, String clientIp) {
        long bonus = ThreadLocalRandom.current().nextLong(1, 10);

        CheckInLog log = new CheckInLog();
        log.setUserId(userId);
        log.setBonus(bonus);
        log.setClientIp(clientIp);
        dbUtil.save(log);

        App.retry(p -> {
            User user = dbUtil.selectByPrimaryKey(userMapper, userId);

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
            balanceLog.setVersion(System.nanoTime());
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
