package org.rx.fl.service.user;

import com.google.common.base.Strings;
import org.rx.annotation.ErrorCode;
import org.rx.beans.DateTime;
import org.rx.beans.Tuple;
import org.rx.common.*;
import org.rx.fl.dto.bot.BotType;
import org.rx.fl.dto.bot.OpenIdInfo;
import org.rx.fl.dto.media.MediaType;
import org.rx.fl.dto.media.OrderStatus;
import org.rx.fl.dto.repo.*;
import org.rx.fl.repository.*;
import org.rx.fl.repository.model.*;
import org.rx.fl.service.NotifyService;
import org.rx.fl.util.DbUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

import static org.rx.common.Contract.require;
import static org.rx.common.Contract.values;
import static org.rx.fl.util.DbUtil.toMoney;

@Service
public class UserService {
    //#region Fields
    private static final byte percentValue = 100;
    @Resource
    private UserConfig userConfig;
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
    @Resource
    private NotifyService notifyService;
    @Resource
    private UserNodeService levelService;
    //#endregion

    //region level
    public long compute(String userId, long rebateAmount) {
        UserNode node = levelService.getNode(userId);
        int percent = checkPercent(node.getPercent());
        return (long) Math.floor((double) rebateAmount * percent / percentValue);
    }

    private int checkPercent(Integer percent) {
        if (percent == null) {
            percent = userConfig.getDefaultPercent();
        }
        if (percent < 0 || percent > percentValue) {
            throw new InvalidOperationException("percent error");
        }
        return percent;
    }
    //endregion

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
        userInfo.setTotalWithdrawAmount(DbUtil.longValue(balanceLogMapper.sumAmount(qBalance)));

        WithdrawLogExample qWithdraw = new WithdrawLogExample();
        qWithdraw.createCriteria().andUserIdEqualTo(user.getId())
                .andStatusEqualTo(WithdrawStatus.Wait.getValue());
        userInfo.setWithdrawingAmount(DbUtil.longValue(withdrawLogMapper.sumAmount(qWithdraw)));

        OrderExample qOrder = new OrderExample();
        qOrder.createCriteria().andUserIdEqualTo(user.getId())
                .andStatusEqualTo(OrderStatus.Paid.getValue());
        userInfo.setUnconfirmedOrderAmount(DbUtil.longValue(orderMapper.sumRebateAmount(qOrder)));
        qOrder = new OrderExample();
        qOrder.createCriteria().andUserIdEqualTo(user.getId())
                .andStatusIn(Arrays.asList(OrderStatus.Success.getValue(), OrderStatus.Settlement.getValue()));
        userInfo.setConfirmedOrderCount(orderMapper.countByExample(qOrder));

        CheckInLogExample query = new CheckInLogExample();
        query.createCriteria().andUserIdEqualTo(user.getId());
        userInfo.setCheckInCount(checkInLogMapper.countByExample(query));
        userInfo.setCheckInAmount(DbUtil.longValue(checkInLogMapper.sumBonus(query)));
        return userInfo;
    }

    @Transactional
    public String getUserId(OpenIdInfo openId) {
        require(openId);

        String userId = App.hash(openId.getBotType().getValue() + openId.getOpenId()).toString();
        User user = userMapper.selectByPrimaryKey(userId);
        if (user == null) {
            user = new User();
            user.setId(userId);
            user.setNickname(openId.getNickname());
            switch (openId.getBotType()) {
                case WxService:
                    user.setWxSvcOpenId(openId.getOpenId());
                    break;
                case Wx:
                    user.setWxOpenId(openId.getOpenId());
                    break;
            }
            dbUtil.save(user, true);
        }
        return user.getId();
    }

    public List<OpenIdInfo> getOpenIds(String userId) {
        require(userId);
        User user = userMapper.selectByPrimaryKey(userId);
        if (user == null) {
            throw new InvalidOperationException("user not found");
        }

        List<OpenIdInfo> openIds = new ArrayList<>();
        if (!Strings.isNullOrEmpty(user.getWxOpenId())) {
            OpenIdInfo wx = new OpenIdInfo();
            wx.setBotType(BotType.Wx);
            wx.setOpenId(user.getWxOpenId());
            wx.setNickname(user.getNickname());
            openIds.add(wx);
        }

        if (!Strings.isNullOrEmpty(user.getWxSvcOpenId())) {
            OpenIdInfo wxSvc = new OpenIdInfo();
            wxSvc.setBotType(BotType.WxService);
            wxSvc.setOpenId(user.getWxSvcOpenId());
            openIds.add(wxSvc);
        }
        return openIds;
    }

    @Transactional
    public void addUserGoods(String userId, MediaType mediaType, String goodsId) {
        require(userId, mediaType, goodsId);

        UserGoods updateRecord = new UserGoods();
        updateRecord.setIsDeleted(DbUtil.IsDeleted_True);
        UserGoodsExample updateQuery = new UserGoodsExample();
        updateQuery.createCriteria()
                .andUserIdEqualTo(userId)
                .andMediaTypeEqualTo(mediaType.getValue())
                .andGoodsIdEqualTo(goodsId);
        userGoodsMapper.updateByExampleSelective(updateRecord, updateQuery);

        UserGoods userGoods = new UserGoods();
        userGoods.setUserId(userId);
        userGoods.setMediaType(mediaType.getValue());
        userGoods.setGoodsId(goodsId);
        dbUtil.save(userGoods);
    }

    public String findUserByGoods(MediaType mediaType, String goodsId) {
        require(mediaType, goodsId);

        UserGoods q = new UserGoods();
        q.setMediaType(mediaType.getValue());
        q.setGoodsId(goodsId);
        q.setCreateTime(DateTime.now().addDays(-1));
        List<String> userIds = userGoodsMapper.selectUserIdByGoods(q);
        if (userIds.size() != 1) {
            return "";
        }
        return userIds.get(0);
    }

    public boolean hasSettleOrder(String userId, String orderId) {
        require(userId, orderId);

        BalanceLogExample q = new BalanceLogExample();
        q.createCriteria().andUserIdEqualTo(userId)
                .andSourceEqualTo(BalanceSourceKind.Order.getValue())
                .andSourceIdEqualTo(orderId);
        return balanceLogMapper.countByExample(q) > 0;
    }

    @Transactional
    public boolean trySettleOrder(String userId, String orderId, Long amount) {
        require(userId, orderId);

        boolean hasSettleOrder = hasSettleOrder(userId, orderId);
        if (!hasSettleOrder) {
            saveUserBalance(userId, BalanceSourceKind.Order, orderId, amount, App.getRequestIp(App.getCurrentRequest()));
            return true;
        }
        return false;
    }

    @Transactional
    public boolean tryRestoreSettleOrder(String userId, String orderId, Long amount) {
        require(userId, orderId);

        boolean hasSettleOrder = hasSettleOrder(userId, orderId);
        if (hasSettleOrder) {
            saveUserBalance(userId, BalanceSourceKind.InvalidOrder, orderId, amount, App.getRequestIp(App.getCurrentRequest()));
            return true;
        }
        return false;
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

        String balanceLogId = saveUserBalance(userId, BalanceSourceKind.Withdraw, null, -money, clientIp).right;

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
        if (feedbackMapper.countByExample(check) > 1) {
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

        saveUserBalance(userId, BalanceSourceKind.CheckIn, log.getId(), bonus, null);

        return bonus;
    }

    private Tuple<User, String> saveUserBalance(String userId, BalanceSourceKind sourceKind, String sourceId, long money, String remark) {
//        require(money, money != 0);
        String clientIp = App.getRequestIp(App.getCurrentRequest());

        return App.retry(2, p -> {
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
            balanceLog.setRemark(remark);
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
        }, null);
    }

    //人工处理提现
    @Transactional
    public String processWithdraw(OpenIdInfo openId, String withdrawId, WithdrawStatus status, String remark) {
        require(openId, withdrawId, status, remark);

        User user = selectUser(openId);
        WithdrawLog withdrawLog = withdrawLogMapper.selectByPrimaryKey(withdrawId);
        if (withdrawLog == null) {
            throw new InvalidOperationException("WithdrawLog not found");
        }

        String content;
        if (status == WithdrawStatus.Fail) {
            String balanceLogId = saveUserBalance(user.getId(), BalanceSourceKind.Withdraw, null, withdrawLog.getAmount(), String.format("提现失败，还原余额%s", toMoney(withdrawLog.getAmount()))).right;
            withdrawLog.setRemark(String.format("提现失败，还原余额流水Id %s", balanceLogId));
            content = String.format("一一一一提 现 失 败一一一一\n" +
                    "提现金额 %.2f元 已返回账户可提现金额里\n" +
                    "失败原因: %s", toMoney(withdrawLog.getAmount()), remark);
        } else {
            withdrawLog.setRemark(remark);
            String account = Strings.isNullOrEmpty(user.getAlipayAccount()) ? String.format("微信 %s", user.getWxOpenId()) : String.format("%s %s", user.getAlipayName(), user.getAlipayAccount());
            content = String.format("一一一一提 现 成 功一一一一\n" +
                    "提现金额: %.2f元\n" +
                    "转入账户: %s\n" +
                    "\n" +
                    "\n" +
                    "将机器人的名片发送给好友，即可享受20%%返利提成", toMoney(withdrawLog.getAmount()), account);
        }
        withdrawLog.setStatus(status.getValue());
        dbUtil.save(withdrawLog);

        notifyService.add(user.getId(), Collections.singletonList(content));
        return withdrawLog.getId();
    }

    //人工校正资金流水
    @Transactional
    public String correctBalance(OpenIdInfo openId, String sourceId, long money, String remark) {
        require(openId, sourceId, remark);

        User user = selectUser(openId);
        return saveUserBalance(user.getId(), BalanceSourceKind.Correct, sourceId, money, remark).right;
    }

    private User selectUser(OpenIdInfo openId) {
        String userId = App.hash(openId.getBotType().getValue() + openId.getOpenId()).toString();
        User user = userMapper.selectByPrimaryKey(userId);
        if (user == null) {
            throw new InvalidOperationException("User not found");
        }
        return user;
    }
}
