package com.wayn.domain.trade.support.order;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wayn.domain.api.common.MybatisPlusTableInfoTestHelper;
import com.wayn.domain.api.outbox.entity.LocalMessage;
import com.wayn.domain.api.outbox.enums.LocalMessageStatusEnum;
import com.wayn.domain.api.outbox.mapper.LocalMessageMapper;
import com.wayn.domain.api.trade.entity.Order;
import com.wayn.domain.api.trade.mapper.OrderMapper;
import com.wayn.domain.api.trade.response.OrderSubmitResultResVO.Status;
import com.wayn.domain.api.trade.service.IOrderGoodsService;
import com.wayn.data.redis.manager.RedisCache;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 覆盖本人持久订单、受理消息和短期缓存之间的故障边界。 */
class OrderSubmitResultTest {
    private final RedisCache redis = mock(RedisCache.class);
    private final OrderMapper orders = mock(OrderMapper.class);
    private final LocalMessageMapper messages = mock(LocalMessageMapper.class);
    private final OrderQuerySupport support = new OrderQuerySupport(redis, orders,
            mock(IOrderGoodsService.class), messages);

    @BeforeAll
    static void initTables() {
        MybatisPlusTableInfoTestHelper.init(Order.class, LocalMessage.class);
    }

    @Test
    void persistedOrderWinsWithoutReadingExpiredOrBrokenCache() {
        Order order = new Order();
        order.setActualPrice(new BigDecimal("18.50"));
        when(orders.selectOne(any(Wrapper.class))).thenAnswer(invocation -> {
            LambdaQueryWrapper<Order> query = invocation.getArgument(0);
            String sql = query.getSqlSegment();
            assertTrue(sql.contains("user_id"));
            assertTrue(sql.contains("order_sn"));
            assertTrue(query.getParamNameValuePairs().containsValue(9L));
            assertTrue(query.getParamNameValuePairs().containsValue("ORDER-1"));
            return order;
        });
        var result = support.searchResult("ORDER-1", 9L);
        assertEquals(Status.SUCCESS, result.status());
        assertEquals(new BigDecimal("18.50"), result.actualPrice());
        verifyNoInteractions(redis, messages);
    }

    @Test
    void ownedPendingAndSentMessagesAreProcessingWhenCacheMissing() {
        for (var status : new LocalMessageStatusEnum[]{LocalMessageStatusEnum.INIT, LocalMessageStatusEnum.SENT}) {
            when(messages.selectOne(any(Wrapper.class))).thenReturn(message(9L, status));
            assertEquals(Status.PROCESSING, support.searchResult("ORDER-1", 9L).status());
        }
    }

    @Test
    void foreignMessageAndMissingMessageHaveSameUnknownResponseAndDoNotReadCache() {
        when(messages.selectOne(any(Wrapper.class))).thenReturn(message(8L, LocalMessageStatusEnum.SENT));
        var foreign = support.searchResult("ORDER-1", 9L);
        when(messages.selectOne(any(Wrapper.class))).thenReturn(null);
        assertEquals(foreign, support.searchResult("ORDER-1", 9L));
        assertEquals(Status.UNKNOWN, foreign.status());
        verifyNoInteractions(redis);
    }

    @Test
    void brokenPayloadAndNullOwnerCannotExposeCachedResults() {
        for (String payload : new String[]{"{broken", "{}", "null", "{\"order\":{}}"}) {
            LocalMessage message = message(9L, LocalMessageStatusEnum.SENT);
            message.setPayload(payload);
            when(messages.selectOne(any(Wrapper.class))).thenReturn(message);
            assertEquals(Status.UNKNOWN, support.searchResult("ORDER-1", 9L).status());
        }
        verifyNoInteractions(redis);
    }

    @Test
    void cacheOutageStillUsesOwnedDurableMessage() {
        when(messages.selectOne(any(Wrapper.class))).thenReturn(message(9L, LocalMessageStatusEnum.INIT));
        when(redis.getCacheObject(anyString())).thenThrow(new IllegalStateException("Redis unavailable"));
        assertEquals(Status.PROCESSING, support.searchResult("ORDER-1", 9L).status());
    }

    @Test
    void cachedSuccessWithoutPersistedOrderDoesNotClaimSuccess() {
        when(messages.selectOne(any(Wrapper.class))).thenReturn(message(9L, LocalMessageStatusEnum.SENT));
        when(redis.getCacheObject(anyString())).thenReturn("success");
        assertEquals(Status.PROCESSING, support.searchResult("ORDER-1", 9L).status());
    }

    @Test
    void retryableErrorAndFailedDeliveryAreUnknownRatherThanTerminalBusinessFailure() {
        when(messages.selectOne(any(Wrapper.class))).thenReturn(message(9L, LocalMessageStatusEnum.SENT));
        when(redis.getCacheObject(anyString())).thenReturn("库存不足");
        assertEquals(Status.UNKNOWN, support.searchResult("ORDER-1", 9L).status());
        when(redis.getCacheObject(anyString())).thenReturn(null);
        when(messages.selectOne(any(Wrapper.class))).thenReturn(message(9L, LocalMessageStatusEnum.FAILED));
        assertEquals(Status.UNKNOWN, support.searchResult("ORDER-1", 9L).status());
    }

    @Test
    void invalidIdentityDoesNotQueryGlobalOrderOrCache() {
        assertEquals(Status.UNKNOWN, support.searchResult("ORDER-1", null).status());
        assertEquals(Status.UNKNOWN, support.searchResult(" ", 9L).status());
        verifyNoInteractions(orders, messages, redis);
    }

    /** 模拟服务端记录的普通下单消息。 */
    private LocalMessage message(Long owner, LocalMessageStatusEnum status) {
        LocalMessage message = new LocalMessage();
        message.setStatus(status.getStatus());
        message.setPayload("{\"order\":{\"userId\":" + owner + "}}");
        return message;
    }
}
