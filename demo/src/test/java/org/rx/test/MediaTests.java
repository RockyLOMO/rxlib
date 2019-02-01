package org.rx.test;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.common.base.Strings;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.rx.beans.DateTime;
import org.rx.common.App;
import org.rx.fl.dto.media.GoodsInfo;
import org.rx.fl.dto.media.OrderInfo;
import org.rx.fl.dto.media.OrderStatus;
import org.rx.fl.service.media.JdMedia;
import org.rx.fl.service.media.Media;
import org.rx.fl.service.media.TbMedia;
import org.rx.util.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.rx.common.Contract.toJsonString;

@Slf4j
public class MediaTests {
    @Test
    public void jdOrders() {
        List<OrderInfo> list = new ArrayList<>();
        JSONObject json;
        do {
            String callback = "{\"code\":200,\"message\":\"success\",\"data\":{\"orderDetailInfos\":[{\"orderId\":83598836634,\"parentId\":0,\"orderTime\":0,\"orderTimeStr\":\"2019-02-01 11:07:33\",\"finishTime\":0,\"finishTimeStr\":\"\",\"payMonth\":0,\"payMonthStr\":\"\",\"validCode\":2,\"validCodeStr\":\"无效-拆单\",\"validCodeUrl\":\"\",\"orderEmt\":0,\"orderEmtStr\":\"无线\",\"plus\":1,\"plusStr\":\"是\",\"isAfterSale\":0,\"orderSkuDetailInfos\":[{\"skuId\":13516730589,\"skuName\":\"德国 进口牛奶 欧德堡（Oldenburger）超高温处理全脂纯牛奶 200ml*24盒\",\"skuNum\":1,\"skuReturnNum\":0,\"frozenSkuNum\":0,\"price\":59.9,\"payPrice\":0,\"commissionRate\":0.5,\"subSideRate\":90,\"subsidyRate\":10,\"finalRate\":100,\"estimateCosPrice\":46.89,\"estimateCommission\":0.23,\"estimateFee\":0,\"actualCosPrice\":0,\"actualCommission\":0,\"actualFee\":0,\"validCode\":2,\"validCodeStr\":\"无效-拆单\",\"traceType\":2,\"traceTypeStr\":\"同店\",\"spId\":0,\"spIdStr\":\"--\",\"siteId\":0,\"unionAlias\":\"\",\"pid\":\"\",\"orderId\":83598836634,\"plus\":1,\"plusStr\":\"是\",\"imgUrl\":\"jfs/t20932/199/1923719245/315388/b25098d6/5b3efac4N9bd84940.jpg\",\"orderTimeStr\":\"2019-02-01 11:07:33\",\"finishTimeStr\":\"\",\"payMonthStr\":\"\",\"validCodeUrl\":\"\",\"subsidyParty\":\"1\",\"subsidyPartyStr\":\"联盟平台\",\"unionTrafficGroup\":5,\"unionTrafficTypeStr\":\"京东\",\"unionTag\":\"00000000\",\"isPinGouStr\":\"否\",\"isJXRedRocket\":\"否\"},{\"skuId\":13038848047,\"skuName\":\"三只松鼠 手撕面包1000g整箱装零食大礼包口袋面包孕妇零食早餐食品生日蛋糕小糕点点心礼盒装\",\"skuNum\":1,\"skuReturnNum\":0,\"frozenSkuNum\":0,\"price\":29.9,\"payPrice\":0,\"commissionRate\":1,\"subSideRate\":90,\"subsidyRate\":0,\"finalRate\":90,\"estimateCosPrice\":23.41,\"estimateCommission\":0.23,\"estimateFee\":0,\"actualCosPrice\":0,\"actualCommission\":0,\"actualFee\":0,\"validCode\":2,\"validCodeStr\":\"无效-拆单\",\"traceType\":2,\"traceTypeStr\":\"同店\",\"spId\":0,\"spIdStr\":\"--\",\"siteId\":0,\"unionAlias\":\"\",\"pid\":\"\",\"orderId\":83598836634,\"plus\":1,\"plusStr\":\"是\",\"imgUrl\":\"jfs/t1/6532/4/2485/610948/5bd12716Ece3e8c51/fc24cc2f4e998b6e.png\",\"orderTimeStr\":\"2019-02-01 11:07:33\",\"finishTimeStr\":\"\",\"payMonthStr\":\"\",\"validCodeUrl\":\"\",\"subsidyParty\":\"\",\"subsidyPartyStr\":\"-\",\"unionTrafficGroup\":5,\"unionTrafficTypeStr\":\"京东\",\"unionTag\":\"00000000\",\"isPinGouStr\":\"否\",\"isJXRedRocket\":\"否\"},{\"skuId\":100000070152,\"skuName\":\"华生（Wahson）取暖器/热水袋/暖手宝/暖水袋/暖宝宝 充电防爆 水电分离WS-01（WZ0564）果绿\",\"skuNum\":1,\"skuReturnNum\":0,\"frozenSkuNum\":0,\"price\":29.9,\"payPrice\":0,\"commissionRate\":1.5,\"subSideRate\":90,\"subsidyRate\":10,\"finalRate\":100,\"estimateCosPrice\":23.41,\"estimateCommission\":0.35,\"estimateFee\":0,\"actualCosPrice\":0,\"actualCommission\":0,\"actualFee\":0,\"validCode\":2,\"validCodeStr\":\"无效-拆单\",\"traceType\":2,\"traceTypeStr\":\"同店\",\"spId\":0,\"spIdStr\":\"--\",\"siteId\":0,\"unionAlias\":\"\",\"pid\":\"\",\"orderId\":83598836634,\"plus\":1,\"plusStr\":\"是\",\"imgUrl\":\"jfs/t1/9404/6/164/532267/5bc9517eEba94ee96/e7d0b9b95bc5fd68.jpg\",\"orderTimeStr\":\"2019-02-01 11:07:33\",\"finishTimeStr\":\"\",\"payMonthStr\":\"\",\"validCodeUrl\":\"\",\"subsidyParty\":\"1\",\"subsidyPartyStr\":\"联盟平台\",\"unionTrafficGroup\":5,\"unionTrafficTypeStr\":\"京东\",\"unionTag\":\"00000000\",\"isPinGouStr\":\"否\",\"isJXRedRocket\":\"否\"}],\"imgUrlS\":[\"jfs/t20932/199/1923719245/315388/b25098d6/5b3efac4N9bd84940.jpg\",\"jfs/t1/6532/4/2485/610948/5bd12716Ece3e8c51/fc24cc2f4e998b6e.png\",\"jfs/t1/9404/6/164/532267/5bc9517eEba94ee96/e7d0b9b95bc5fd68.jpg\"],\"skuLists\":[],\"payPriceSum\":93.71,\"commissionRateOrderStr\":\"详情\",\"finalRateOrderStr\":\"详情\",\"estimateFeeOrder\":0,\"actualFeeOrder\":0,\"actualCosPriceOrder\":0,\"unionTrafficGroup\":5,\"unionTrafficTypeStr\":\"京东\",\"spId\":0,\"spIdStr\":\"--\",\"siteId\":0,\"pid\":\"\",\"isPinGouStr\":\"否\",\"isJXRedRocket\":\"否\"},{\"orderId\":83594607381,\"parentId\":83598836634,\"orderTime\":0,\"orderTimeStr\":\"2019-02-01 11:07:33\",\"finishTime\":0,\"finishTimeStr\":\"\",\"payMonth\":0,\"payMonthStr\":\"\",\"validCode\":16,\"validCodeStr\":\"已付款\",\"validCodeUrl\":\"\",\"orderEmt\":0,\"orderEmtStr\":\"无线\",\"plus\":1,\"plusStr\":\"是\",\"isAfterSale\":0,\"orderSkuDetailInfos\":[{\"skuId\":13038848047,\"skuName\":\"三只松鼠 手撕面包1000g整箱装零食大礼包口袋面包孕妇零食早餐食品生日蛋糕小糕点点心礼盒装\",\"skuNum\":1,\"skuReturnNum\":0,\"frozenSkuNum\":0,\"price\":29.9,\"payPrice\":0,\"commissionRate\":1,\"subSideRate\":90,\"subsidyRate\":0,\"finalRate\":90,\"estimateCosPrice\":17.9,\"estimateCommission\":0.18,\"estimateFee\":0.16,\"actualCosPrice\":0,\"actualCommission\":0,\"actualFee\":0,\"validCode\":16,\"validCodeStr\":\"已付款\",\"traceType\":2,\"traceTypeStr\":\"同店\",\"spId\":0,\"spIdStr\":\"--\",\"siteId\":0,\"unionAlias\":\"\",\"pid\":\"\",\"orderId\":83594607381,\"plus\":1,\"plusStr\":\"是\",\"imgUrl\":\"jfs/t1/6532/4/2485/610948/5bd12716Ece3e8c51/fc24cc2f4e998b6e.png\",\"orderTimeStr\":\"2019-02-01 11:07:33\",\"finishTimeStr\":\"\",\"payMonthStr\":\"\",\"validCodeUrl\":\"\",\"subsidyParty\":\"\",\"subsidyPartyStr\":\"-\",\"unionTrafficGroup\":5,\"unionTrafficTypeStr\":\"京东\",\"unionTag\":\"00000000\",\"isPinGouStr\":\"否\",\"isJXRedRocket\":\"否\"}],\"imgUrlS\":[\"jfs/t1/6532/4/2485/610948/5bd12716Ece3e8c51/fc24cc2f4e998b6e.png\"],\"skuLists\":[],\"payPriceSum\":17.9,\"commissionRateOrderStr\":\"1.00\",\"finalRateOrderStr\":\"90.00\",\"estimateFeeOrder\":0.16,\"actualFeeOrder\":0,\"actualCosPriceOrder\":0,\"unionTrafficGroup\":5,\"unionTrafficTypeStr\":\"京东\",\"spId\":0,\"spIdStr\":\"--\",\"siteId\":0,\"pid\":\"\",\"isPinGouStr\":\"否\",\"isJXRedRocket\":\"否\"},{\"orderId\":83593928509,\"parentId\":83598836634,\"orderTime\":0,\"orderTimeStr\":\"2019-02-01 11:07:33\",\"finishTime\":0,\"finishTimeStr\":\"\",\"payMonth\":0,\"payMonthStr\":\"\",\"validCode\":16,\"validCodeStr\":\"已付款\",\"validCodeUrl\":\"\",\"orderEmt\":0,\"orderEmtStr\":\"无线\",\"plus\":1,\"plusStr\":\"是\",\"isAfterSale\":0,\"orderSkuDetailInfos\":[{\"skuId\":13516730589,\"skuName\":\"德国 进口牛奶 欧德堡（Oldenburger）超高温处理全脂纯牛奶 200ml*24盒\",\"skuNum\":1,\"skuReturnNum\":0,\"frozenSkuNum\":0,\"price\":59.9,\"payPrice\":0,\"commissionRate\":0.5,\"subSideRate\":90,\"subsidyRate\":10,\"finalRate\":100,\"estimateCosPrice\":50.9,\"estimateCommission\":0.25,\"estimateFee\":0.25,\"actualCosPrice\":0,\"actualCommission\":0,\"actualFee\":0,\"validCode\":16,\"validCodeStr\":\"已付款\",\"traceType\":2,\"traceTypeStr\":\"同店\",\"spId\":0,\"spIdStr\":\"--\",\"siteId\":0,\"unionAlias\":\"\",\"pid\":\"\",\"orderId\":83593928509,\"plus\":1,\"plusStr\":\"是\",\"imgUrl\":\"jfs/t20932/199/1923719245/315388/b25098d6/5b3efac4N9bd84940.jpg\",\"orderTimeStr\":\"2019-02-01 11:07:33\",\"finishTimeStr\":\"\",\"payMonthStr\":\"\",\"validCodeUrl\":\"\",\"subsidyParty\":\"1\",\"subsidyPartyStr\":\"联盟平台\",\"unionTrafficGroup\":5,\"unionTrafficTypeStr\":\"京东\",\"unionTag\":\"00000000\",\"isPinGouStr\":\"否\",\"isJXRedRocket\":\"否\"}],\"imgUrlS\":[\"jfs/t20932/199/1923719245/315388/b25098d6/5b3efac4N9bd84940.jpg\"],\"skuLists\":[],\"payPriceSum\":50.9,\"commissionRateOrderStr\":\"0.50\",\"finalRateOrderStr\":\"100.00\",\"estimateFeeOrder\":0.25,\"actualFeeOrder\":0,\"actualCosPriceOrder\":0,\"unionTrafficGroup\":5,\"unionTrafficTypeStr\":\"京东\",\"spId\":0,\"spIdStr\":\"--\",\"siteId\":0,\"pid\":\"\",\"isPinGouStr\":\"否\",\"isJXRedRocket\":\"否\"},{\"orderId\":83579541627,\"parentId\":83598836634,\"orderTime\":0,\"orderTimeStr\":\"2019-02-01 11:07:33\",\"finishTime\":0,\"finishTimeStr\":\"2019-02-01 16:15:28\",\"payMonth\":0,\"payMonthStr\":\"\",\"validCode\":17,\"validCodeStr\":\"已完成\",\"validCodeUrl\":\"\",\"orderEmt\":0,\"orderEmtStr\":\"无线\",\"plus\":1,\"plusStr\":\"是\",\"isAfterSale\":0,\"orderSkuDetailInfos\":[{\"skuId\":100000070152,\"skuName\":\"华生（Wahson）取暖器/热水袋/暖手宝/暖水袋/暖宝宝 充电防爆 水电分离WS-01（WZ0564）果绿\",\"skuNum\":1,\"skuReturnNum\":0,\"frozenSkuNum\":0,\"price\":29.9,\"payPrice\":23.9,\"commissionRate\":1.5,\"subSideRate\":90,\"subsidyRate\":10,\"finalRate\":100,\"estimateCosPrice\":24.9,\"estimateCommission\":0.37,\"estimateFee\":0.37,\"actualCosPrice\":26.9,\"actualCommission\":0.4,\"actualFee\":0.4,\"validCode\":17,\"validCodeStr\":\"已完成\",\"traceType\":2,\"traceTypeStr\":\"同店\",\"spId\":0,\"spIdStr\":\"--\",\"siteId\":0,\"unionAlias\":\"\",\"pid\":\"\",\"orderId\":83579541627,\"plus\":1,\"plusStr\":\"是\",\"imgUrl\":\"jfs/t1/9404/6/164/532267/5bc9517eEba94ee96/e7d0b9b95bc5fd68.jpg\",\"orderTimeStr\":\"2019-02-01 11:07:33\",\"finishTimeStr\":\"2019-02-01 16:15:28\",\"payMonthStr\":\"\",\"validCodeUrl\":\"\",\"subsidyParty\":\"1\",\"subsidyPartyStr\":\"联盟平台\",\"unionTrafficGroup\":5,\"unionTrafficTypeStr\":\"京东\",\"unionTag\":\"00000000\",\"isPinGouStr\":\"否\",\"isJXRedRocket\":\"否\"}],\"imgUrlS\":[\"jfs/t1/9404/6/164/532267/5bc9517eEba94ee96/e7d0b9b95bc5fd68.jpg\"],\"skuLists\":[],\"payPriceSum\":24.9,\"commissionRateOrderStr\":\"1.50\",\"finalRateOrderStr\":\"100.00\",\"estimateFeeOrder\":0.37,\"actualFeeOrder\":0.4,\"actualCosPriceOrder\":26.9,\"unionTrafficGroup\":5,\"unionTrafficTypeStr\":\"京东\",\"spId\":0,\"spIdStr\":\"--\",\"siteId\":0,\"pid\":\"\",\"isPinGouStr\":\"否\",\"isJXRedRocket\":\"否\"},{\"orderId\":86053762470,\"parentId\":0,\"orderTime\":0,\"orderTimeStr\":\"2019-01-25 16:34:16\",\"finishTime\":0,\"finishTimeStr\":\"2019-01-27 09:01:15\",\"payMonth\":0,\"payMonthStr\":\"\",\"validCode\":17,\"validCodeStr\":\"已完成\",\"validCodeUrl\":\"\",\"orderEmt\":0,\"orderEmtStr\":\"无线\",\"plus\":1,\"plusStr\":\"是\",\"isAfterSale\":0,\"orderSkuDetailInfos\":[{\"skuId\":32641401757,\"skuName\":\"威刚（ADATA）XPG 龙耀D41  RGB幻光渐层内存 DDR4 台式机电脑内存灯条 单条【8G】 2666 频率\",\"skuNum\":1,\"skuReturnNum\":0,\"frozenSkuNum\":0,\"price\":429,\"payPrice\":402,\"commissionRate\":1.8,\"subSideRate\":90,\"subsidyRate\":0,\"finalRate\":90,\"estimateCosPrice\":404,\"estimateCommission\":7.27,\"estimateFee\":6.54,\"actualCosPrice\":402,\"actualCommission\":7.24,\"actualFee\":6.52,\"validCode\":17,\"validCodeStr\":\"已完成\",\"traceType\":2,\"traceTypeStr\":\"同店\",\"spId\":0,\"spIdStr\":\"--\",\"siteId\":0,\"unionAlias\":\"\",\"pid\":\"\",\"orderId\":86053762470,\"plus\":1,\"plusStr\":\"是\",\"imgUrl\":\"jfs/t1/14451/33/5692/91502/5c419750E93662438/ac74aa942d0ef01a.jpg\",\"orderTimeStr\":\"2019-01-25 16:34:16\",\"finishTimeStr\":\"2019-01-27 09:01:15\",\"payMonthStr\":\"\",\"validCodeUrl\":\"\",\"subsidyParty\":\"\",\"subsidyPartyStr\":\"-\",\"unionTrafficGroup\":5,\"unionTrafficTypeStr\":\"京东\",\"unionTag\":\"00000000\",\"isPinGouStr\":\"否\",\"isJXRedRocket\":\"否\"}],\"imgUrlS\":[\"jfs/t1/14451/33/5692/91502/5c419750E93662438/ac74aa942d0ef01a.jpg\"],\"skuLists\":[],\"payPriceSum\":404,\"commissionRateOrderStr\":\"1.80\",\"finalRateOrderStr\":\"90.00\",\"estimateFeeOrder\":6.54,\"actualFeeOrder\":6.52,\"actualCosPriceOrder\":402,\"unionTrafficGroup\":5,\"unionTrafficTypeStr\":\"京东\",\"spId\":0,\"spIdStr\":\"--\",\"siteId\":0,\"pid\":\"\",\"isPinGouStr\":\"否\",\"isJXRedRocket\":\"否\"},{\"orderId\":83410173301,\"parentId\":0,\"orderTime\":0,\"orderTimeStr\":\"2019-01-25 16:27:24\",\"finishTime\":0,\"finishTimeStr\":\"2019-01-27 09:01:19\",\"payMonth\":0,\"payMonthStr\":\"\",\"validCode\":17,\"validCodeStr\":\"已完成\",\"validCodeUrl\":\"\",\"orderEmt\":0,\"orderEmtStr\":\"无线\",\"plus\":1,\"plusStr\":\"是\",\"isAfterSale\":0,\"orderSkuDetailInfos\":[{\"skuId\":32641401757,\"skuName\":\"威刚（ADATA）XPG 龙耀D41  RGB幻光渐层内存 DDR4 台式机电脑内存灯条 单条【8G】 2666 频率\",\"skuNum\":1,\"skuReturnNum\":0,\"frozenSkuNum\":0,\"price\":429,\"payPrice\":397,\"commissionRate\":1.8,\"subSideRate\":90,\"subsidyRate\":0,\"finalRate\":90,\"estimateCosPrice\":399,\"estimateCommission\":7.18,\"estimateFee\":6.46,\"actualCosPrice\":397,\"actualCommission\":7.15,\"actualFee\":6.44,\"validCode\":17,\"validCodeStr\":\"已完成\",\"traceType\":2,\"traceTypeStr\":\"同店\",\"spId\":0,\"spIdStr\":\"--\",\"siteId\":0,\"unionAlias\":\"\",\"pid\":\"\",\"orderId\":83410173301,\"plus\":1,\"plusStr\":\"是\",\"imgUrl\":\"jfs/t1/14451/33/5692/91502/5c419750E93662438/ac74aa942d0ef01a.jpg\",\"orderTimeStr\":\"2019-01-25 16:27:24\",\"finishTimeStr\":\"2019-01-27 09:01:19\",\"payMonthStr\":\"\",\"validCodeUrl\":\"\",\"subsidyParty\":\"\",\"subsidyPartyStr\":\"-\",\"unionTrafficGroup\":5,\"unionTrafficTypeStr\":\"京东\",\"unionTag\":\"00000000\",\"isPinGouStr\":\"否\",\"isJXRedRocket\":\"否\"}],\"imgUrlS\":[\"jfs/t1/14451/33/5692/91502/5c419750E93662438/ac74aa942d0ef01a.jpg\"],\"skuLists\":[],\"payPriceSum\":399,\"commissionRateOrderStr\":\"1.80\",\"finalRateOrderStr\":\"90.00\",\"estimateFeeOrder\":6.46,\"actualFeeOrder\":6.44,\"actualCosPriceOrder\":397,\"unionTrafficGroup\":5,\"unionTrafficTypeStr\":\"京东\",\"spId\":0,\"spIdStr\":\"--\",\"siteId\":0,\"pid\":\"\",\"isPinGouStr\":\"否\",\"isJXRedRocket\":\"否\"},{\"orderId\":86028950082,\"parentId\":0,\"orderTime\":0,\"orderTimeStr\":\"2019-01-25 10:13:26\",\"finishTime\":0,\"finishTimeStr\":\"2019-01-25 17:17:47\",\"payMonth\":0,\"payMonthStr\":\"\",\"validCode\":17,\"validCodeStr\":\"已完成\",\"validCodeUrl\":\"\",\"orderEmt\":0,\"orderEmtStr\":\"无线\",\"plus\":1,\"plusStr\":\"是\",\"isAfterSale\":0,\"orderSkuDetailInfos\":[{\"skuId\":8302704,\"skuName\":\"华硕（ASUS）TUF B450M-PLUS GAMING电竞特工 游戏主板 支持2400G/2600X/2700X CPU（AMD B450/ Socket AM4）\",\"skuNum\":1,\"skuReturnNum\":0,\"frozenSkuNum\":0,\"price\":719,\"payPrice\":611,\"commissionRate\":0.9,\"subSideRate\":90,\"subsidyRate\":10,\"finalRate\":100,\"estimateCosPrice\":619,\"estimateCommission\":5.57,\"estimateFee\":5.57,\"actualCosPrice\":611,\"actualCommission\":5.5,\"actualFee\":5.5,\"validCode\":17,\"validCodeStr\":\"已完成\",\"traceType\":2,\"traceTypeStr\":\"同店\",\"spId\":0,\"spIdStr\":\"--\",\"siteId\":0,\"unionAlias\":\"\",\"pid\":\"\",\"orderId\":86028950082,\"plus\":1,\"plusStr\":\"是\",\"imgUrl\":\"jfs/t25852/249/1209486759/212766/195b9cb4/5b8e43e0N342435f1.jpg\",\"orderTimeStr\":\"2019-01-25 10:13:26\",\"finishTimeStr\":\"2019-01-25 17:17:47\",\"payMonthStr\":\"\",\"validCodeUrl\":\"\",\"subsidyParty\":\"1\",\"subsidyPartyStr\":\"联盟平台\",\"unionTrafficGroup\":5,\"unionTrafficTypeStr\":\"京东\",\"unionTag\":\"00000000\",\"isPinGouStr\":\"否\",\"isJXRedRocket\":\"否\"}],\"imgUrlS\":[\"jfs/t25852/249/1209486759/212766/195b9cb4/5b8e43e0N342435f1.jpg\"],\"skuLists\":[],\"payPriceSum\":619,\"commissionRateOrderStr\":\"0.90\",\"finalRateOrderStr\":\"100.00\",\"estimateFeeOrder\":5.57,\"actualFeeOrder\":5.5,\"actualCosPriceOrder\":611,\"unionTrafficGroup\":5,\"unionTrafficTypeStr\":\"京东\",\"spId\":0,\"spIdStr\":\"--\",\"siteId\":0,\"pid\":\"\",\"isPinGouStr\":\"否\",\"isJXRedRocket\":\"否\"},{\"orderId\":85949140226,\"parentId\":0,\"orderTime\":0,\"orderTimeStr\":\"2019-01-24 10:47:53\",\"finishTime\":0,\"finishTimeStr\":\"2019-01-24 17:34:11\",\"payMonth\":0,\"payMonthStr\":\"\",\"validCode\":17,\"validCodeStr\":\"已完成\",\"validCodeUrl\":\"\",\"orderEmt\":0,\"orderEmtStr\":\"无线\",\"plus\":1,\"plusStr\":\"是\",\"isAfterSale\":0,\"orderSkuDetailInfos\":[{\"skuId\":100001024471,\"skuName\":\"光威(Gloway)TYPE-α系列 DDR4 8G 3000 台式机电脑内存条(石墨灰)\",\"skuNum\":1,\"skuReturnNum\":1,\"frozenSkuNum\":0,\"price\":409,\"payPrice\":387,\"commissionRate\":0,\"subSideRate\":90,\"subsidyRate\":10,\"finalRate\":100,\"estimateCosPrice\":389,\"estimateCommission\":0,\"estimateFee\":0,\"actualCosPrice\":0,\"actualCommission\":0,\"actualFee\":0,\"validCode\":17,\"validCodeStr\":\"已完成\",\"traceType\":2,\"traceTypeStr\":\"同店\",\"spId\":1554718140,\"spIdStr\":\"rx\",\"siteId\":0,\"unionAlias\":\"\",\"pid\":\"\",\"orderId\":85949140226,\"plus\":1,\"plusStr\":\"是\",\"imgUrl\":\"jfs/t25525/328/2330921984/133200/41b2f543/5bc84b53Ncdbe031d.jpg\",\"orderTimeStr\":\"2019-01-24 10:47:53\",\"finishTimeStr\":\"2019-01-24 17:34:11\",\"payMonthStr\":\"\",\"validCodeUrl\":\"\",\"subsidyParty\":\"1\",\"subsidyPartyStr\":\"联盟平台\",\"unionTrafficGroup\":5,\"unionTrafficTypeStr\":\"京东\",\"unionTag\":\"00000000\",\"isPinGouStr\":\"否\",\"isJXRedRocket\":\"否\"}],\"imgUrlS\":[\"jfs/t25525/328/2330921984/133200/41b2f543/5bc84b53Ncdbe031d.jpg\"],\"skuLists\":[],\"payPriceSum\":389,\"commissionRateOrderStr\":\"0.00\",\"finalRateOrderStr\":\"100.00\",\"estimateFeeOrder\":0,\"actualFeeOrder\":0,\"actualCosPriceOrder\":0,\"unionTrafficGroup\":5,\"unionTrafficTypeStr\":\"京东\",\"spId\":1554718140,\"spIdStr\":\"rx\",\"siteId\":0,\"pid\":\"\",\"isPinGouStr\":\"否\",\"isJXRedRocket\":\"否\"},{\"orderId\":85950983789,\"parentId\":0,\"orderTime\":0,\"orderTimeStr\":\"2019-01-24 10:44:18\",\"finishTime\":0,\"finishTimeStr\":\"2019-01-24 17:34:11\",\"payMonth\":0,\"payMonthStr\":\"\",\"validCode\":17,\"validCodeStr\":\"已完成\",\"validCodeUrl\":\"\",\"orderEmt\":0,\"orderEmtStr\":\"无线\",\"plus\":1,\"plusStr\":\"是\",\"isAfterSale\":0,\"orderSkuDetailInfos\":[{\"skuId\":100001024471,\"skuName\":\"光威(Gloway)TYPE-α系列 DDR4 8G 3000 台式机电脑内存条(石墨灰)\",\"skuNum\":1,\"skuReturnNum\":1,\"frozenSkuNum\":0,\"price\":409,\"payPrice\":381,\"commissionRate\":0,\"subSideRate\":90,\"subsidyRate\":10,\"finalRate\":100,\"estimateCosPrice\":384,\"estimateCommission\":0,\"estimateFee\":0,\"actualCosPrice\":0,\"actualCommission\":0,\"actualFee\":0,\"validCode\":17,\"validCodeStr\":\"已完成\",\"traceType\":2,\"traceTypeStr\":\"同店\",\"spId\":1554718140,\"spIdStr\":\"rx\",\"siteId\":0,\"unionAlias\":\"\",\"pid\":\"\",\"orderId\":85950983789,\"plus\":1,\"plusStr\":\"是\",\"imgUrl\":\"jfs/t25525/328/2330921984/133200/41b2f543/5bc84b53Ncdbe031d.jpg\",\"orderTimeStr\":\"2019-01-24 10:44:18\",\"finishTimeStr\":\"2019-01-24 17:34:11\",\"payMonthStr\":\"\",\"validCodeUrl\":\"\",\"subsidyParty\":\"1\",\"subsidyPartyStr\":\"联盟平台\",\"unionTrafficGroup\":5,\"unionTrafficTypeStr\":\"京东\",\"unionTag\":\"00000000\",\"isPinGouStr\":\"否\",\"isJXRedRocket\":\"否\"}],\"imgUrlS\":[\"jfs/t25525/328/2330921984/133200/41b2f543/5bc84b53Ncdbe031d.jpg\"],\"skuLists\":[],\"payPriceSum\":384,\"commissionRateOrderStr\":\"0.00\",\"finalRateOrderStr\":\"100.00\",\"estimateFeeOrder\":0,\"actualFeeOrder\":0,\"actualCosPriceOrder\":0,\"unionTrafficGroup\":5,\"unionTrafficTypeStr\":\"京东\",\"spId\":1554718140,\"spIdStr\":\"rx\",\"siteId\":0,\"pid\":\"\",\"isPinGouStr\":\"否\",\"isJXRedRocket\":\"否\"}],\"page\":{\"pageNo\":1,\"pageSize\":100,\"hasMore\":false},\"moreData\":false}}";
            log.info("findOrders callbackValue {}", callback);
            json = JSON.parseObject(callback);
            if (json.getIntValue("code") != 200) {
                log.info("check login");
                break;
            }
            json = json.getJSONObject("data");
            JSONArray orders = json.getJSONArray("orderDetailInfos");
            if (orders.isEmpty()) {
                log.info("no data");
                break;
            }

            for (int i = 0; i < orders.size(); i++) {
                JSONObject row = orders.getJSONObject(i);
                OrderInfo order = JsonMapper.Default.convertTo(OrderInfo.class, "jdQueryOrderDetail", row);
                switch (row.getString("validCodeStr").trim()) {
                    case "已结算":
                        order.setStatus(OrderStatus.Settlement);
                        break;
                    case "已完成":
                        order.setStatus(OrderStatus.Success);
                        break;
                    case "已付款":
                        order.setStatus(OrderStatus.Paid);
                        break;
                    default:
                        order.setStatus(OrderStatus.Invalid);
                        break;
                }
                list.add(order);
            }
        } while (json.getBooleanValue("moreData"));
        System.out.println(JSON.toJSONString(list));
    }

    static final Function<String, Double> convert = p -> {
        if (Strings.isNullOrEmpty(p)) {
            return 0d;
        }
        return App.changeType(p.replace("￥", "")
                .replace("¥", "").replace("元", ""), double.class);
    };

    @SneakyThrows
    @Test
    public void jdMedia() {
        String userMessage = "https://u.jd.com/bdJddY";
        userMessage = "https://u.jd.com/lRB5js";
//        userMessage = "https://item.jd.com/23030257143.html";

        JdMedia media = new JdMedia();

//        String url = media.findLink(userMessage);
//        assert url != null;
//        GoodsInfo goods = media.findGoods(url);
//        assert goods != null;

//        media.login();
//        String code = media.findAdv(goods);
//
//        Double payAmount = convert.apply(goods.getPrice())
//                - convert.apply(goods.getRebateAmount())
//                - convert.apply(goods.getCouponAmount());
//        String content = String.format("约反%s 优惠券%s 付费价￥%.2f；复制框内整段文字，打开「手淘」即可「领取优惠券」并购买%s",
//                goods.getRebateAmount(), goods.getCouponAmount(), payAmount, code);
//        System.out.println(content);

        List<OrderInfo> orders = media.findOrders(DateTime.now().addDays(-17), DateTime.now());
        System.out.println(toJsonString(orders));

        System.in.read();
    }

    @SneakyThrows
    @Test
    public void tbMedia() {
        String userMessage =
                "https://item.taobao.com/item.htm?spm=a230r.1.14.316.5aec2f1f1KyoMC&id=580168318999&ns=1&abbucket=1#detail";

        TbMedia media = new TbMedia();
        media.setShareCookie(false);

        String url = media.findLink(userMessage);
        assert url != null;
        GoodsInfo goods = media.findGoods(url);
        assert goods != null;

        media.login();
        String code = media.findAdv(goods);

        Double payAmount = convert.apply(goods.getPrice())
                - convert.apply(goods.getRebateAmount())
                - convert.apply(goods.getCouponAmount());
        String content = String.format("约反%s 优惠券%s 付费价￥%.2f；复制框内整段文字，打开「手淘」即可「领取优惠券」并购买%s",
                goods.getRebateAmount(), goods.getCouponAmount(), payAmount, code);
        System.out.println(content);

        List<OrderInfo> orders = media.findOrders(DateTime.now().addDays(-7), DateTime.now());
        System.out.println(toJsonString(orders));

        System.in.read();
    }
}
