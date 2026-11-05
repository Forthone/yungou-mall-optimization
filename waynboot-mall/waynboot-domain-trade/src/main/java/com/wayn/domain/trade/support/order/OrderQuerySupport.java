package com.wayn.domain.trade.support.order;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.wayn.domain.api.outbox.entity.LocalMessage;
import com.wayn.domain.api.outbox.mapper.LocalMessageMapper;
import com.wayn.domain.api.outbox.enums.LocalMessageStatusEnum;
import com.wayn.domain.api.outbox.service.LocalMessageTopics;
import com.wayn.domain.api.trade.response.OrderSubmitResultResVO;
import com.wayn.domain.api.trade.response.OrderSubmitResultResVO.Status;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import java.util.Objects;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wayn.domain.api.trade.entity.Order;
import com.wayn.domain.api.trade.entity.OrderGoods;
import com.wayn.domain.api.trade.mapper.OrderMapper;
import com.wayn.domain.api.trade.service.IOrderGoodsService;
import com.wayn.domain.api.trade.response.OrderDetailVO;
import com.wayn.domain.api.trade.response.OrderGoodsVO;
import com.wayn.domain.api.trade.response.OrderListDataResVO;
import com.wayn.domain.api.trade.response.OrderListResVO;
import com.wayn.domain.api.trade.response.OrderStatusCountResVO;
import com.wayn.common.util.OrderUtil;
import com.wayn.data.redis.manager.RedisCache;
import com.wayn.util.enums.ReturnCodeEnum;
import com.wayn.util.exception.BusinessException;
import com.wayn.util.util.bean.MyBeanUtil;
import lombok.AllArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.wayn.data.redis.constant.RedisKeyEnum.ORDER_RESULT_KEY;

/**
 * 订单查询支撑服务。
 * 聚合订单列表、详情和提交结果查询，避免查询逻辑与写流程互相耦合。
 */
@Slf4j
@Service
@AllArgsConstructor
public class OrderQuerySupport {

    private final RedisCache redisCache;
    private final OrderMapper orderMapper;
    private final IOrderGoodsService orderGoodsService;
    private final LocalMessageMapper localMessageMapper;

    /**
     * 查询用户订单列表。
     *
     * @param page 分页参数
     * @param showType 展示类型
     * @param userId 用户 ID
     * @return 订单列表结果
     */
    public OrderListResVO selectListPage(IPage<Order> page, Integer showType, Long userId) {
        List<Short> orderStatusList = OrderUtil.orderStatus(showType);
        Order query = new Order();
        query.setUserId(userId);
        IPage<Order> orderPage = orderMapper.selectOrderListPage(page, query, orderStatusList);
        List<Order> orders = orderPage.getRecords();
        Map<Long, List<OrderGoods>> orderGoodsMap = listOrderGoodsMap(orders);
        List<OrderListDataResVO> dataList = buildOrderListData(orders, orderGoodsMap);

        OrderListResVO resVO = new OrderListResVO();
        resVO.setData(dataList);
        resVO.setPage(orderPage.getCurrent());
        resVO.setPages(orderPage.getPages());
        return resVO;
    }

    /**
     * 批量查询订单商品并按订单 ID 分组。
     * 列表页先拿订单主表分页，再一次性拉取商品快照，避免每个订单单独查询造成 N+1。
     *
     * @param orders 当前页订单列表
     * @return orderId 到订单商品列表的映射
     */
    private Map<Long, List<OrderGoods>> listOrderGoodsMap(List<Order> orders) {
        List<Long> orderIds = orders.stream().map(Order::getId).toList();
        if (CollectionUtils.isEmpty(orderIds)) {
            return Collections.emptyMap();
        }
        return orderGoodsService.list(Wrappers.lambdaQuery(OrderGoods.class)
                        .in(OrderGoods::getOrderId, orderIds))
                .stream()
                .collect(Collectors.groupingBy(OrderGoods::getOrderId));
    }

    /**
     * 构建订单列表返回数据。
     * 该方法只做订单主表和订单商品快照的 VO 组装，不触发数据库访问。
     *
     * @param orders 当前页订单列表
     * @param orderGoodsMap orderId 到订单商品列表的映射
     * @return 订单列表 VO
     */
    private List<OrderListDataResVO> buildOrderListData(List<Order> orders, Map<Long, List<OrderGoods>> orderGoodsMap) {
        List<OrderListDataResVO> dataList = new ArrayList<>(orders.size());
        for (Order order : orders) {
            dataList.add(buildOrderListDataItem(order,
                    orderGoodsMap.getOrDefault(order.getId(), Collections.emptyList())));
        }
        return dataList;
    }

    /**
     * 构建单个订单列表项。
     *
     * @param order 订单主表
     * @param orderGoodsList 订单商品快照
     * @return 订单列表项
     */
    private OrderListDataResVO buildOrderListDataItem(Order order, List<OrderGoods> orderGoodsList) {
        OrderListDataResVO data = new OrderListDataResVO();
        data.setId(order.getId());
        data.setOrderSn(order.getOrderSn());
        data.setActualPrice(order.getActualPrice());
        data.setHandleOption(OrderUtil.build(order));
        data.setOrderStatusText(OrderUtil.orderStatusText(order));
        data.setGoodsList(BeanUtil.copyToList(orderGoodsList, OrderGoodsVO.class));
        return data;
    }

    /**
     * 统计用户各订单状态数量。
     *
     * @param userId 用户 ID
     * @return 状态统计结果
     */
    public OrderStatusCountResVO statusCount(Long userId) {
        List<Order> orderList = orderMapper.selectList(new QueryWrapper<Order>()
                .select("order_status", "comments")
                .eq("user_id", userId));
        long unpaid = 0;
        long unship = 0;
        long unrecv = 0;
        long uncomment = 0;
        for (Order order : orderList) {
            if (OrderUtil.isCreateStatus(order)) {
                unpaid++;
            } else if (OrderUtil.isPayStatus(order)) {
                unship++;
            } else if (OrderUtil.isShipStatus(order)) {
                unrecv++;
            } else if (OrderUtil.isConfirmStatus(order) || OrderUtil.isAutoConfirmStatus(order)) {
                uncomment += Optional.ofNullable(order.getComments()).orElse(0);
            }
        }

        OrderStatusCountResVO resVO = new OrderStatusCountResVO();
        resVO.setUncomment(uncomment);
        resVO.setUnrecv(unrecv);
        resVO.setUnship(unship);
        resVO.setUnpaid(unpaid);
        return resVO;
    }

    /**
     * 根据订单号查询订单详情。
     *
     * @param orderSn 订单号
     * @return 订单详情
     */
    public OrderDetailVO getOrderDetailByOrderSn(String orderSn) {
        Order order = orderMapper.selectOne(Wrappers.lambdaQuery(Order.class)
                .eq(Order::getOrderSn, orderSn));
        if (order == null) {
            throw new BusinessException(ReturnCodeEnum.ORDER_NOT_EXISTS_ERROR);
        }

        OrderDetailVO orderDetailVO = new OrderDetailVO();
        MyBeanUtil.copyProperties(order, orderDetailVO);
        orderDetailVO.setOrderStatusText(OrderUtil.orderStatusText(order));
        orderDetailVO.setPayTypeText(OrderUtil.payTypeText(order));
        List<OrderGoods> orderGoodsList = orderGoodsService.list(Wrappers.lambdaQuery(OrderGoods.class)
                .eq(OrderGoods::getOrderId, order.getId()));
        orderDetailVO.setOrderGoodsVOList(BeanUtil.copyToList(orderGoodsList, OrderGoodsVO.class));
        return orderDetailVO;
    }

    /**
     * 只读查询普通订单创建结果，不发消息、不改库存、不回写缓存。
     * 数据库中的本人订单是成功依据；Outbox 只证明请求受理及归属，不能证明最终失败。
     *
     * @param orderSn 订单号
     * @param userId 认证上下文中的用户 ID，不能由前端指定
     * @return 已创建、处理中或无法确认；不暴露其他用户的处理信息
     */
    public OrderSubmitResultResVO searchResult(String orderSn, Long userId) {
        if (userId == null || StringUtils.isBlank(orderSn)) {
            return unknownResult();
        }
        // 先查本人持久化订单，覆盖缓存过期、缓存异常、过期失败提示和支付后状态变化。
        Order order = orderMapper.selectOne(Wrappers.lambdaQuery(Order.class)
                .eq(Order::getOrderSn, orderSn)
                .eq(Order::getUserId, userId));
        if (order != null) {
            return new OrderSubmitResultResVO(Status.SUCCESS, "订单已创建", order.getActualPrice());
        }
        LocalMessage message = localMessageMapper.selectOne(Wrappers.lambdaQuery(LocalMessage.class)
                .eq(LocalMessage::getBizType, LocalMessageTopics.BIZ_TYPE_ORDER)
                .eq(LocalMessage::getBizId, orderSn)
                .eq(LocalMessage::getTopic, LocalMessageTopics.ORDER_SUBMIT));
        // 没有订单时，必须先从服务端保存的消息参数核实归属，再读取按订单号存储的缓存。
        if (!isSubmitMessageOwner(message, userId)) {
            return unknownResult();
        }
        try {
            String cached = redisCache.getCacheObject(ORDER_RESULT_KEY.getKey(orderSn));
            // 回调异常仍可能重试，错误文本仅表示一次尝试失败，不将它当成不可逆终态。
            if (StringUtils.isNotBlank(cached) && !"success".equals(cached)) {
                return unknownResult();
            }
        } catch (RuntimeException e) {
            log.warn("下单结果缓存不可用，使用持久记录判断, orderSn={}, userId={}", orderSn, userId);
        }
        if (Objects.equals(message.getStatus(), LocalMessageStatusEnum.INIT.getStatus())
                || Objects.equals(message.getStatus(), LocalMessageStatusEnum.SENT.getStatus())) {
            return new OrderSubmitResultResVO(Status.PROCESSING, "订单处理中，请稍候", null);
        }
        // 投递失败不能证明没有消费成功，也可能后续人工补偿，不提示用户立即重新下单。
        return unknownResult();
    }

    /** 核对服务端持久消息中的用户；旧数据或损坏载荷一律按未知处理，日志不输出载荷。 */
    private boolean isSubmitMessageOwner(LocalMessage message, Long userId) {
        if (message == null || StringUtils.isBlank(message.getPayload())) {
            return false;
        }
        try {
            JSONObject body = JSON.parseObject(message.getPayload());
            JSONObject submit = body == null ? null : body.getJSONObject("order");
            return submit != null && Objects.equals(userId, submit.getLong("userId"));
        } catch (RuntimeException e) {
            log.warn("下单消息无法解析，返回未知结果, messageId={}", message.getId());
            return false;
        }
    }

    /** 未知、越权和缺失记录返回相同文案，避免泄露订单是否属于其他账号。 */
    private OrderSubmitResultResVO unknownResult() {
        return new OrderSubmitResultResVO(Status.UNKNOWN, "暂时无法确认下单结果，请稍后查看我的订单", null);
    }
}
