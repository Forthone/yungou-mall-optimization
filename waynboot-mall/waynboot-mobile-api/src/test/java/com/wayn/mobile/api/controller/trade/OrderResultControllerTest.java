package com.wayn.mobile.api.controller.trade;

import com.wayn.domain.api.trade.response.OrderSubmitResultResVO;
import com.wayn.domain.api.trade.response.OrderSubmitResultResVO.Status;
import com.wayn.domain.api.trade.service.IMobileOrderService;
import com.wayn.mobile.framework.security.util.MobileSecurityUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderResultControllerTest {
    @Test
    void passesAuthenticatedUserAndReturnsProcessingDataWithoutBusinessError() {
        IMobileOrderService service = mock(IMobileOrderService.class);
        var expected = new OrderSubmitResultResVO(Status.PROCESSING, "订单处理中", null);
        when(service.searchResult("ORDER-1", 9L)).thenReturn(expected);
        try (var security = mockStatic(MobileSecurityUtils.class)) {
            security.when(MobileSecurityUtils::getUserId).thenReturn(9L);
            var response = new OrderController(service).searchResult("ORDER-1");
            assertEquals(200, response.getCode());
            assertEquals(expected, response.getData());
            verify(service).searchResult("ORDER-1", 9L);
        }
    }
}
