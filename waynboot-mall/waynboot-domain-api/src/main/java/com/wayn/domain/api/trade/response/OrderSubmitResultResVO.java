package com.wayn.domain.api.trade.response;

import java.math.BigDecimal;

/**
 * 普通订单创建结果。成功只代表订单已落库，不代表支付成功。
 * 未找到订单或发生可重试错误时不能推断业务最终失败。
 */
public record OrderSubmitResultResVO(Status status, String message, BigDecimal actualPrice) {

    /** 查询能够确认的状态；当前没有持久化的业务终态失败记录。 */
    public enum Status {
        SUCCESS, PROCESSING, UNKNOWN
    }
}
