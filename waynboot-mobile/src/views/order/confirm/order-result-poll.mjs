export const ORDER_RESULT_UNKNOWN_MESSAGE = '暂时无法确认下单结果，请稍后查看我的订单'

/**
 * 有限次数查询普通订单创建结果。网络异常和等待超时均不是业务失败。
 * query 只读取结果，不重新提交订单；离开页面后由 signal 取消后续查询。
 */
export async function waitForOrderResult(query, {
  attempts = 30,
  interval = 1000,
  signal,
  wait = (delay) => new Promise((resolve) => setTimeout(resolve, delay)),
} = {}) {
  for (let index = 0; index < attempts; index += 1) {
    if (signal?.aborted) return null
    let result
    try {
      result = (await query())?.data
    } catch {
      // 查询网络故障不代表消费失败，继续有限轮询，最终保守返回未知。
    }
    if (signal?.aborted) return null
    if (result?.status === 'SUCCESS' || result?.status === 'UNKNOWN') return result
    if (index + 1 < attempts) await wait(interval)
  }
  return { status: 'UNKNOWN', message: ORDER_RESULT_UNKNOWN_MESSAGE }
}
