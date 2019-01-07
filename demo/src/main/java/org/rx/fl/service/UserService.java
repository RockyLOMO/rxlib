package org.rx.fl.service;

import com.google.common.base.Strings;
import org.rx.*;
import org.rx.bean.DateTime;
import org.rx.bean.Tuple;
import org.rx.fl.model.MediaType;
import org.rx.fl.model.OrderStatus;
import org.rx.fl.repository.*;
import org.rx.fl.repository.model.*;
import org.rx.fl.service.dto.*;
import org.rx.fl.util.DbUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.rx.Contract.require;
import static org.rx.Contract.values;
import static org.rx.fl.util.DbUtil.toMoney;

@Service
public class UserService {
    @Resource
    private UserMapper userMapper;
    @Resource
    private BalanceLogMapper balanceLogMapper;
    @Resource
    private WithdrawLogMapper withdrawLogMapper;
    @Resource
    private CheckInLogMapper checkInLogMapper;
    @Resource
    private FeedbackMapper feedbackMapper;
    @Resource
    private OrderMapper orderMapper;
    @Resource
    private UserGoodsMapper userGoodsMapper;
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

        WithdrawLogExample qWithdraw = new WithdrawLogExample();
        qWithdraw.createCriteria().andUserIdEqualTo(user.getId())
                .andStatusEqualTo(WithdrawStatus.Wait.getValue());
        userInfo.setWithdrawingAmount(withdrawLogMapper.sumAmount(qWithdraw));

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

    public String findUserByGoods(MediaType mediaType, String goodsId) {
        require(mediaType, goodsId);

        UserGoodsExample q = new UserGoodsExample();
        q.setLimit(2);
        q.createCriteria().andMediaTypeEqualTo(mediaType.ordinal())
                .andGoodsIdEqualTo(goodsId)
                .andCreateTimeGreaterThanOrEqualTo(DateTime.now().addDays(-1))
                .andIsDeletedEqualTo(DbUtil.IsDeleted_False);
        List<UserGoods> userGoodsList = userGoodsMapper.selectByExample(q);
        if (userGoodsList.size() != 1) {
            return "";
        }

        UserGoods userGoods = userGoodsList.get(0);

        UserGoods toUpdate = new UserGoods();
        toUpdate.setId(userGoods.getId());
        toUpdate.setIsDeleted(DbUtil.IsDeleted_True);
        userGoodsMapper.updateByPrimaryKeySelective(toUpdate);

        return userGoods.getUserId();
    }

    @ErrorCode("notEnoughBalance")
    @ErrorCode(value = "withdrawing", messageKeys = {"$amount"})
    @Transactional
    public WithdrawResult withdraw(String userId, String clientIp) {
        require(userId);
        User user = dbUtil.selectById(userMapper, userId);
        if (user.getBalance() == 0) {
            throw new SystemException(values(), "notEnoughBalance");
        }
        WithdrawLogExample check = new WithdrawLogExample();
        check.createCriteria().andUserIdEqualTo(userId)
                .andStatusEqualTo(WithdrawStatus.Wait.getValue());
        NQuery<WithdrawLog> q = NQuery.of(withdrawLogMapper.selectByExample(check));
        if (q.any()) {
            throw new SystemException(values(String.format("%.2f", q.sum(p -> toMoney(p.getAmount())))), "withdrawing");
        }

        long money = user.getBalance();

        String balanceLogId = saveUserBalance(userId, clientIp, BalanceSourceKind.Withdraw, null, -money).right;

        WithdrawLog log = new WithdrawLog();
        log.setUserId(user.getId());
        log.setBalanceLogId(balanceLogId);
        log.setAmount(money);
        log.setStatus(WithdrawStatus.Wait.getValue());
        dbUtil.save(log);

        WithdrawResult result = new WithdrawResult();
        result.setUserId(user.getId());
        result.setWithdrawAmount(money);
        result.setFreezeAmount(user.getFreezeAmount());
        result.setHasAliPay(!Strings.isNullOrEmpty(user.getAlipayAccount()));
        return result;
    }

    @ErrorCode("alreadyBind")
    @Transactional
    public void bindPayment(String userId, String aliPayName, String aliPayAccount) {
        require(userId, aliPayName, aliPayAccount);

        User user = new User();
        user.setAlipayName(aliPayName);
        user.setAlipayAccount(aliPayAccount);
        UserExample query = new UserExample();
        query.createCriteria().andIdEqualTo(userId)
                .andAlipayAccountIsNull();
        int r = userMapper.updateByExampleSelective(user, query);
        if (r != 1) {
            throw new SystemException(values(), "alreadyBind");
        }
    }

    @ErrorCode("alreadyCommit")
    @Transactional
    public void feedback(String userId, String msg) {
        require(userId, msg);
        User user = dbUtil.selectById(userMapper, userId);

        FeedbackExample check = new FeedbackExample();
        check.createCriteria().andUserIdEqualTo(user.getId()).andStatusEqualTo(FeedbackStatus.WaitReply.getValue());
        if (feedbackMapper.countByExample(check) > 0) {
            throw new SystemException(values(), "alreadyCommit");
        }

        Feedback feedback = new Feedback();
        feedback.setUserId(user.getId());
        feedback.setContent(msg);
        feedback.setStatus(FeedbackStatus.WaitReply.getValue());
        dbUtil.save(feedback);
    }

    @ErrorCode("alreadyCheckIn")
    @Transactional
    public long checkIn(String userId, String clientIp) {
        require(userId, clientIp);

        CheckInLogExample check = new CheckInLogExample();
        DateTime now = DateTime.now();
        check.createCriteria().andUserIdEqualTo(userId)
                .andCreateTimeLessThan(now).andCreateTimeGreaterThanOrEqualTo(now.getDateComponent());
        if (checkInLogMapper.countByExample(check) > 0) {
            throw new SystemException(values(), "alreadyCheckIn");
        }

        long bonus = ThreadLocalRandom.current().nextLong(1, 10);

        CheckInLog log = new CheckInLog();
        log.setUserId(userId);
        log.setBonus(bonus);
        log.setClientIp(clientIp);
        dbUtil.save(log);

        saveUserBalance(userId, clientIp, BalanceSourceKind.CheckIn, log.getId(), bonus);

        return bonus;
    }

    Tuple<User, String> saveUserBalance(String userId, String clientIp, BalanceSourceKind sourceKind, String sourceId, long money) {
        require(money, money != 0);

        return App.retry(p -> {
            User user = dbUtil.selectById(userMapper, userId);

            BalanceLog balanceLog = new BalanceLog();
            balanceLog.setUserId(user.getId());
            balanceLog.setType((money > 0 ? BalanceType.Income : BalanceType.Expense).getValue());
            balanceLog.setSource(sourceKind.getValue());
            balanceLog.setSourceId(sourceId);

            balanceLog.setPreBalance(user.getBalance());
            user.setBalance(user.getBalance() + money);
            balanceLog.setPostBalance(user.getBalance());
            balanceLog.setValue(money);

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
            return Tuple.of(user, balanceLog.getId());
        }, null, 2);
    }
}
