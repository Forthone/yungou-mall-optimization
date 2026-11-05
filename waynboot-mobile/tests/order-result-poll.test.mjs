import test from 'node:test'
import assert from 'node:assert/strict'
import { waitForOrderResult } from '../src/views/order/confirm/order-result-poll.mjs'

const fast = { interval: 0, wait: async () => {} }

test('处理中继续查询，成功时保留数据库金额，包括零元订单', async () => {
  let calls = 0
  const result = await waitForOrderResult(async () => ({ data: ++calls === 1
    ? { status: 'PROCESSING' } : { status: 'SUCCESS', actualPrice: 0 } }), fast)
  assert.equal(calls, 2)
  assert.equal(result.status, 'SUCCESS')
  assert.equal(result.actualPrice, 0)
})

test('未知结果立即停止，不把响应成功当作订单成功', async () => {
  let calls = 0
  const result = await waitForOrderResult(async () => {
    calls += 1
    return { data: { status: 'UNKNOWN' } }
  }, fast)
  assert.equal(calls, 1)
  assert.equal(result.status, 'UNKNOWN')
})

test('持续处理中或网络异常到达上限均返回未知，不返回下单失败', async () => {
  for (const query of [async () => ({ data: { status: 'PROCESSING' } }), async () => { throw new Error('offline') }]) {
    const result = await waitForOrderResult(query, { ...fast, attempts: 2 })
    assert.equal(result.status, 'UNKNOWN')
    assert.match(result.message, /查看我的订单/)
  }
})

test('离开页面取消后不再轮询，也不返回成功触发跳转', async () => {
  const controller = new AbortController()
  let calls = 0
  const result = await waitForOrderResult(async () => {
    calls += 1
    controller.abort()
    return { data: { status: 'SUCCESS' } }
  }, { ...fast, signal: controller.signal })
  assert.equal(result, null)
  assert.equal(calls, 1)
})
