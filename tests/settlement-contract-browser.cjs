// T14 controlled financial UI fixtures, maintained with the real buyer/admin suite.
// Only financial responses are fixtures; no external bank request or funds mutation.
const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert/strict')
async function checkSettlementContract({
  page,
  admin,
  context,
  origin,
  adminOrigin,
  output,
  mark,
  errors,
}) {
  const out = path.join(output, 'settlement-contract')
  fs.mkdirSync(out, { recursive: true })
  const checks = []
  await page.setViewportSize({ width: 1440, height: 1000 })
  await admin.setViewportSize({ width: 1440, height: 1000 })
  let paymentConfigured = true,
    bankVisited = false,
    restockRequests = 0,
    returnRequests = 0,
    returnBusy = false,
    returnDelay = 0,
    syncPays = true,
    syncBusy = false,
    syncRequests = 0,
    syncDelay = 0,
    holdPayment = false,
    releasePayment,
    paymentStarted,
    paymentFinished,
    holdAdmin = false,
    releaseAdmin,
    adminStarted,
    adminFinished,
    refundBusy = false,
    refundUnconfirmed = false,
    holdRefund = false,
    releaseRefund,
    refundStarted,
    refundFinished
  const check = (name) => {
    checks.push(name)
    mark('UI contract: ' + name)
  }
  const go = (target, base, url) => {
    const u = new URL(url)
    return target.goto(new URL(u.pathname.slice(1) + u.search, base).href)
  }
  const at = new Date().toISOString(),
    id = 900001,
    saleId = 900101,
    itemId = 900201
  const order = {
    id,
    orderNo: 'UI-CONTRACT-900001',
    accountId: 2,
    status: 0,
    statusName: '待支付',
    totalCents: 12900,
    itemCount: 1,
    receiver: '界面测试收件人',
    phone: '18800000001',
    region: '虚构测试区域',
    addressDetail: '接口替身场景，无实物收货',
    createdAt: at,
    expiresAt: new Date(Date.now() + 1200000).toISOString(),
    carrier: null,
    trackingNo: null,
    paidAt: null,
    shippedAt: null,
    completedAt: null,
    closedAt: null,
    closeReason: null,
  }
  const items = [
    {
      id: itemId,
      orderId: id,
      productId: 1001,
      name: '界面分支验证 · 蜡烛',
      spec: '180g',
      unit: '杯',
      imagePath: '/assets/candle.png',
      priceCents: 12900,
      quantity: 1,
      subtotalCents: 12900,
    },
  ]
  const funds = { payment: null, afterSale: null, refund: null, reviews: [] }
  function sale() {
    return {
      id: saleId,
      orderId: id,
      orderNo: order.orderNo,
      orderStatus: order.status,
      totalCents: 12900,
      reason: '界面分支测试（模拟接口）',
      state: 'REQUESTED',
      reviewNote: '',
      returnCarrier: null,
      returnTrackingNo: null,
      receivedAt: null,
      restocked: false,
      createdAt: at,
    }
  }
  await context.route(
    'https://openapi-sandbox.dl.alipaydev.com/**',
    async (route) => {
      if (route.request().headers().authorization)
        throw Error('Bearer leaked to bank')
      bankVisited = true
      await route.fulfill({
        contentType: 'text/html',
        body: '<!doctype html><html lang="zh-CN"><title>测试银行 · 界面分支验证</title><body><h1>测试银行，仅用于页面分支验证</h1></body></html>',
      })
    },
  )
  await context.route('**/api/v1/**', async (route) => {
    const req = route.request(),
      url = new URL(req.url()),
      p = url.pathname.replace('/api/v1', ''),
      method = req.method()
    if (p === `/admin/orders/${id}` && holdAdmin) {
      adminStarted?.()
      await new Promise((resolve) => {
        releaseAdmin = resolve
      })
    }
    let body
    let matched = true
    const input = req.postData() ? JSON.parse(req.postData()) : {}
    if (p === '/capabilities')
      body = { paymentConfigured, advisorConfigured: false }
    else if (
      (p === `/orders/${id}` || p === `/admin/orders/${id}`) &&
      method === 'GET'
    )
      body = { order, items }
    else if (p === '/admin/orders' && method === 'GET')
      body = { items: [order], total: 1, page: 1, pageSize: 12 }
    else if (
      p === `/orders/${id}/settlement` ||
      p === `/admin/orders/${id}/settlement`
    )
      body = funds
    else if (p === `/orders/${id}/payment` && method === 'POST') {
      if (req.postData()) throw Error('Client supplied payment data')
      if (holdPayment) {
        paymentStarted?.()
        await new Promise((resolve) => {
          releasePayment = resolve
        })
      }
      funds.payment = {
        orderId: id,
        status: 'PENDING',
        tradeNo: null,
        amountCents: null,
        successAt: null,
        needsReview: false,
      }
      body = {
        url: 'https://openapi-sandbox.dl.alipaydev.com/gateway.do?ui_contract=1',
      }
    } else if (
      p === `/orders/${id}/payment/sync` ||
      p === `/admin/orders/${id}/payment/sync`
    ) {
      syncRequests++
      if (syncDelay)
        await new Promise((resolve) => setTimeout(resolve, syncDelay))
      if (syncBusy)
        return route.fulfill({
          status: 503,
          json: {
            resultCode: 503,
            errorCode: 'PAYMENT_CHECK_PENDING',
            message: '支付平台暂时无法核对，请稍后重试，不必重复付款',
            traceId: 'ui-expected-platform-busy',
            data: null,
          },
        })
      if (!syncPays)
        return route.fulfill({ json: { resultCode: 200, data: funds } })
      order.status = 1
      order.statusName = '已支付'
      order.paidAt = at
      funds.payment = {
        orderId: id,
        status: 'SUCCESS',
        tradeNo: 'UI-BANK-900001',
        amountCents: 12900,
        successAt: at,
        needsReview: false,
      }
      body = funds
    } else if (p === `/orders/${id}/after-sale`) {
      funds.afterSale = sale()
      funds.afterSale.reason = input.reason
      body = funds.afterSale
    } else if (p === `/after-sales/${saleId}/return`) {
      returnRequests++
      if (returnDelay) await new Promise((resolve) => setTimeout(resolve, returnDelay))
      if (returnBusy) return route.fulfill({ status: 503, json: { resultCode: 503, errorCode: 'TEST_RETURN_UNAVAILABLE', message: '物流暂未保存，请重试', traceId: 'return-retry-test' } })
      funds.afterSale.returnCarrier = input.carrier
      funds.afterSale.returnTrackingNo = input.trackingNo
      body = funds.afterSale
    } else if (p === `/orders/${id}/reviews`) {
      const r = {
        id: 900301,
        orderItemId: itemId,
        stars: input.stars,
        content: input.content,
        displayName: '界面虚构评价',
        createdAt: at,
      }
      funds.reviews = [r]
      body = r
    } else if (p === '/catalog/products/1001/reviews')
      body = {
        items: funds.reviews,
        total: funds.reviews.length,
        page: 1,
        pageSize: 12,
      }
    else if (p === '/admin/after-sales')
      body = {
        items: funds.afterSale ? [funds.afterSale] : [],
        total: funds.afterSale ? 1 : 0,
        page: 1,
        pageSize: 12,
      }
    else if (p === `/admin/after-sales/${saleId}/review`) {
      funds.afterSale.reviewNote = input.note
      funds.afterSale.state = input.approve
        ? order.status >= 3
          ? 'RETURN_REQUIRED'
          : 'REFUND_PENDING'
        : 'REJECTED'
      body = funds.afterSale
    } else if (p === `/admin/after-sales/${saleId}/receive`) {
      funds.afterSale.state = 'REFUND_PENDING'
      funds.afterSale.receivedAt = at
      body = funds.afterSale
    } else if (p === `/admin/after-sales/${saleId}/refund`) {
      if (refundUnconfirmed) {
        funds.refund = { afterSaleId: saleId, requestNo: 'UI-REFUND-900001', status: 'FAILED', amountCents: 12900, successAt: null, lastErrorCode: 'REFUND_NOT_CONFIRMED' }
        return route.fulfill({ json: { resultCode: 200, data: funds.refund } })
      }
      if (refundBusy) {
        funds.refund = {
          afterSaleId: saleId,
          requestNo: 'UI-REFUND-900001',
          status: 'PENDING',
          amountCents: 12900,
          successAt: null,
          lastErrorCode: 'RESULT_UNKNOWN',
        }
        return route.fulfill({
          status: 503,
          json: {
            resultCode: 503,
            errorCode: 'REFUND_CHECK_PENDING',
            message: '退款结果尚待核对，请稍后使用原退款申请重试',
            traceId: 'ui-expected-refund-busy',
            data: null,
          },
        })
      }
      if (holdRefund) {
        refundStarted?.()
        await new Promise((resolve) => {
          releaseRefund = resolve
        })
      }
      funds.afterSale.state = 'REFUNDED'
      funds.refund = {
        afterSaleId: saleId,
        requestNo: 'UI-REFUND-900001',
        status: 'SUCCESS',
        amountCents: 12900,
        successAt: at,
        lastErrorCode: null,
      }
      body = funds.refund
    } else if (p === `/admin/after-sales/${saleId}/restock`) {
      if (!input.reason) throw Error('Missing restock reason')
      funds.afterSale.restocked = true
      restockRequests++
      body = funds.afterSale
    } else if (p === '/admin/finance') {
      const from = url.searchParams.get('from'),
        to = url.searchParams.get('to')
      body = {
        from,
        to,
        paidCents: 12900,
        refundedCents: 12900,
        netCents: 0,
        days: [
          {
            date: from,
            paidCents: 12900,
            refundedCents: 0,
            netCents: 12900,
            paymentCount: 1,
            refundCount: 0,
          },
          {
            date: to,
            paidCents: 0,
            refundedCents: 12900,
            netCents: -12900,
            paymentCount: 0,
            refundCount: 1,
          },
        ],
      }
    } else matched = false
    if (!matched) return route.continue()
    await route.fulfill({
      status: 200,
      json: {
        resultCode: 200,
        message: 'UI contract fixture',
        data: body,
        errorCode: null,
        traceId: 'ui-contract-fixture',
      },
    })
    if (p === `/orders/${id}/payment`) paymentFinished?.()
    if (p === `/admin/orders/${id}`) adminFinished?.()
    if (p === `/admin/after-sales/${saleId}/refund`) refundFinished?.()
  })
  syncPays = false
  funds.payment = {
    orderId: id,
    status: 'PENDING',
    tradeNo: null,
    amountCents: null,
    successAt: null,
    needsReview: false,
  }
  order.expiresAt = new Date(Date.now() - 60000).toISOString()
  await go(page, origin, `http://127.0.0.1:15183/payment/${id}`)
  await page
    .getByRole('heading', { name: '付款时间已过', exact: true })
    .waitFor()
  assert.equal(
    await page.getByRole('button', { name: '前往支付宝沙箱' }).count(),
    0,
  )
  await page.getByRole('button', { name: '核对付款结果' }).click()
  await page
    .getByRole('heading', { name: '付款时间已过', exact: true })
    .waitFor()
  assert.equal(funds.payment.status, 'PENDING')
  await page.screenshot({
    path: path.join(out, 'expired-payment-desktop.png'),
    fullPage: true,
  })
  await go(page, origin, `http://127.0.0.1:15183/orders/${id}`)
  await page
    .getByRole('heading', { name: '付款时间已过', exact: true })
    .waitFor()
  assert.equal(
    await page.getByRole('link', { name: '继续付款', exact: true }).count(),
    0,
  )
  await page.getByRole('link', { name: '查看付款状态', exact: true }).waitFor()
  check('expired payment entry and order detail preserve unconfirmed funds')

  await go(admin, adminOrigin, 'http://127.0.0.1:15184/orders')
  await admin.getByRole('button', { name: '查看处理', exact: true }).click()
  await admin
    .getByRole('button', { name: '查询平台付款结果', exact: true })
    .waitFor()
  await admin
    .locator('.el-drawer')
    .getByText('付款时间已过', { exact: true })
    .waitFor()
  syncDelay = 200
  const adminQueries = syncRequests
  await admin
    .getByRole('button', { name: '查询平台付款结果', exact: true })
    .dblclick()
  await admin
    .getByRole('button', { name: '查询平台付款结果', exact: true })
    .waitFor()
  await admin.getByText('待平台核对', { exact: true }).waitFor()
  assert.equal(syncRequests - adminQueries, 1)
  syncDelay = 0
  await admin.screenshot({
    path: path.join(out, 'admin-expired-pending.png'),
    fullPage: true,
  })
  check('admin query refreshes pending drawer once and preserves expired state')

  // A response arriving after the operator closes the drawer cannot reopen it.
  await admin.locator('.el-drawer__close-btn').click()
  await admin
    .getByRole('dialog', { name: '订单详情', exact: true })
    .waitFor({ state: 'hidden' })
  holdAdmin = true
  const adminRequest = new Promise((resolve) => {
    adminStarted = resolve
  })
  const adminDone = new Promise((resolve) => {
    adminFinished = resolve
  })
  await admin.getByRole('button', { name: '查看处理', exact: true }).click()
  await adminRequest
  await admin.getByText('正在读取订单…', { exact: true }).waitFor()
  await admin.locator('.el-drawer__close-btn').click()
  const adminResponse = admin.waitForResponse(
    (r) => new URL(r.url()).pathname === `/api/v1/admin/orders/${id}`,
  )
  releaseAdmin()
  await adminDone
  await adminResponse
  await admin.waitForLoadState('networkidle')
  assert.equal(
    await admin
      .getByRole('dialog', { name: '订单详情', exact: true })
      .isVisible(),
    false,
  )
  holdAdmin = false
  check('closing loading admin drawer discards its late response')

  order.expiresAt = new Date(Date.now() + 6000).toISOString()
  await go(page, origin, `http://127.0.0.1:15183/payment/${id}`)
  await page.getByRole('button', { name: '前往支付宝沙箱' }).waitFor()
  await page
    .getByRole('heading', { name: '付款时间已过', exact: true })
    .waitFor()
  assert.equal(
    await page.getByRole('button', { name: '前往支付宝沙箱' }).count(),
    0,
  )
  check('open payment page removes entry as its deadline passes')

  order.expiresAt = new Date(Date.now() + 1200000).toISOString()
  await page.reload()
  await page.getByRole('button', { name: '前往支付宝沙箱' }).waitFor()
  syncBusy = true
  const errorStart = errors.length
  await page.getByRole('button', { name: '核对付款结果' }).click()
  await page
    .getByRole('heading', { name: '付款结果待核对', exact: true })
    .waitFor()
  assert.equal(
    await page.getByRole('button', { name: '前往支付宝沙箱' }).count(),
    0,
  )
  assert.equal(funds.payment.status, 'PENDING')
  // Only the exact injected HTTP 503 resource message is expected. All JS
  // exceptions and other response errors still fail the suite.
  for (const error of errors.splice(errorStart))
    assert.match(error, /^Failed to load resource:.*status of 503/)
  await page.screenshot({
    path: path.join(out, 'platform-busy-recovery.png'),
    fullPage: true,
  })
  syncBusy = false
  syncDelay = 200
  const requestsBefore = syncRequests
  await page.getByRole('button', { name: '核对付款结果' }).dblclick()
  await page.getByRole('button', { name: '前往支付宝沙箱' }).waitFor()
  assert.equal(syncRequests - requestsBefore, 1)
  syncDelay = 0
  check('platform busy stays pending and double query submits once')

  holdPayment = true
  const started = new Promise((resolve) => {
    paymentStarted = resolve
  })
  const finished = new Promise((resolve) => {
    paymentFinished = resolve
  })
  await page.getByRole('button', { name: '前往支付宝沙箱' }).click()
  await started
  await page.getByRole('link', { name: '查看订单详情', exact: true }).click()
  await page.getByRole('heading', { name: '待支付', exact: true }).waitFor()
  const response = page.waitForResponse((r) =>
    r.url().endsWith(`/orders/${id}/payment`),
  )
  releasePayment()
  await finished
  await response
  await page.waitForLoadState('networkidle')
  assert(new URL(page.url()).pathname === `/orders/${id}`)
  assert.equal(bankVisited, false)
  holdPayment = false
  check('leaving payment while request is pending cannot redirect to cashier')

  syncPays = true
  await go(page, origin, `http://127.0.0.1:15183/payment/${id}`)
  await page.getByRole('button', { name: '前往支付宝沙箱' }).click()
  await page.waitForURL('https://openapi-sandbox.dl.alipaydev.com/**')
  if (!bankVisited) throw Error('Bank route not visited')
  check('controlled bank URL without Bearer or client amount')
  await go(page, origin, `http://127.0.0.1:15183/payment/${id}?returned=1`)
  await page.getByRole('region', { name: '付款成功', exact: true }).waitFor()
  if (
    await page
      .getByRole('button', { name: '前往支付宝沙箱', exact: true })
      .count()
  )
    throw Error('Already paid order offers a second payment')
  check('return triggers backend status query; no client paid mutation')
  await page.getByRole('link', { name: '查看订单详情' }).click()
  await page.getByRole('button', { name: '申请整单售后' }).click()
  await page
    .getByLabel('申请原因', { exact: true })
    .fill('界面分支测试（模拟接口）')
  await page.getByRole('button', { name: '提交申请', exact: true }).click()
  await page.getByRole('heading', { name: '待审核', exact: true }).waitFor()
  check('buyer whole-order after-sale form')
  await go(
    admin,
    adminOrigin,
    `http://127.0.0.1:15184/after-sales?orderId=${id}`,
  )
  await admin.getByRole('heading', { name: '待审核', exact: true }).waitFor()
  await admin
    .getByLabel('审核说明', { exact: true })
    .fill('同意整单处理，界面模拟验证。')
  await admin.getByRole('button', { name: '同意整单售后' }).click()
  await admin
    .getByRole('heading', { name: '待退款核对', exact: true })
    .waitFor()
  refundBusy = true
  const refundErrorStart = errors.length
  await admin.getByRole('button', { name: '核对并处理整单退款' }).click()
  await admin.getByRole('button', { name: '确认', exact: true }).click()
  await admin
    .getByText('退款结果尚待核对，请稍后使用原退款申请重试', { exact: false })
    .waitFor()
  assert.equal(funds.refund.status, 'PENDING')
  assert.equal(
    await admin.getByRole('button', { name: '确认可售并入库' }).count(),
    0,
  )
  for (const error of errors.splice(refundErrorStart))
    assert.match(error, /^Failed to load resource:.*status of 503/)
  refundBusy = false
  check('unknown refund refreshes pending intent without offering restock')
  refundUnconfirmed = true
  await admin.getByRole('button', { name: '核对并处理整单退款' }).click()
  await admin.getByRole('button', { name: '确认', exact: true }).click()
  await admin.getByText('退款尚未确认，请查看最新进度并使用原申请核对', { exact: true }).waitFor()
  assert.equal(funds.refund.requestNo, 'UI-REFUND-900001')
  assert.equal(funds.afterSale.state, 'REFUND_PENDING')
  assert.equal(await admin.getByRole('button', { name: '确认可售并入库' }).count(), 0)
  refundUnconfirmed = false
  check('successful HTTP with unconfirmed refund warns without claiming money returned')

  holdRefund = true
  const refundRequest = new Promise((resolve) => {
    refundStarted = resolve
  })
  const refundDone = new Promise((resolve) => {
    refundFinished = resolve
  })
  await admin.getByRole('button', { name: '核对并处理整单退款' }).click()
  await admin.getByRole('button', { name: '确认', exact: true }).click()
  await refundRequest
  await admin.locator('.after-sale-drawer .el-drawer__close-btn').click()
  const refundResponse = admin.waitForResponse(
    (r) =>
      new URL(r.url()).pathname ===
      `/api/v1/admin/after-sales/${saleId}/refund`,
  )
  releaseRefund()
  await refundDone
  await refundResponse
  await admin.waitForLoadState('networkidle')
  assert.equal(
    await admin
      .getByRole('dialog', { name: '售后处理', exact: true })
      .isVisible(),
    false,
  )
  holdRefund = false
  check('late refund response cannot reopen a closed after-sale drawer')
  await admin.reload()
  await admin
    .getByRole('heading', { name: '整单已退款', exact: true })
    .waitFor()
  await admin
    .getByLabel('可售入库原因', { exact: true })
    .fill('界面测试：确认完整可售')
  await admin.getByRole('button', { name: '确认可售并入库' }).click()
  await admin.getByRole('button', { name: '确认', exact: true }).click()
  await admin.getByText('已恢复一次', { exact: true }).waitFor()
  if (restockRequests !== 1) throw Error('Duplicate restock UI call')
  check('admin review/refund/restock are separate actions')
  await page.reload()
  await page.getByRole('heading', { name: '整单已退款', exact: true }).waitFor()
  await page.screenshot({
    path: path.join(out, 'buyer-refund-contract.png'),
    fullPage: true,
  })
  order.status = 3
  order.statusName = '出库成功'
  order.carrier = '虚构快递'
  order.trackingNo = 'OUT900001'
  funds.afterSale = sale()
  funds.afterSale.state = 'RETURN_REQUIRED'
  funds.afterSale.reviewNote = '请按约定退回虚构测试服务台。'
  funds.refund = null
  await page.reload()
  await page.getByLabel('承运方', { exact: true }).fill('测试退回快递')
  await page.getByLabel('运单号', { exact: true }).fill('RETURN900001')
  await page.getByRole('button', { name: '保存退回物流' }).click()
  await page
    .getByText('退回物流：测试退回快递 · RETURN900001', { exact: true })
    .waitFor()
  if (returnRequests !== 1) throw Error('Return not submitted')
  await page.getByRole('heading', { name: '待店铺确认收货', exact: true }).waitFor()
  assert.equal(await page.getByLabel('运单号', { exact: true }).count(), 0)
  await page.getByRole('status').filter({ hasText: '退回物流已保存' }).waitFor()
  await page.locator('.sale-progress').screenshot({ path: path.join(out, 'return-recorded-desktop.png') })
  check('buyer saved return switches to confirmation and hides entry form')
  await page.getByRole('button', { name: '修改退回物流', exact: true }).click()
  assert.equal(await page.getByLabel('承运方', { exact: true }).inputValue(), '测试退回快递')
  assert.equal(await page.getByLabel('运单号', { exact: true }).inputValue(), 'RETURN900001')
  await page.getByLabel('运单号', { exact: true }).fill('CANCELLED900001')
  await page.getByRole('button', { name: '取消修改', exact: true }).click()
  assert.equal(returnRequests, 1)
  await page.getByText('退回物流：测试退回快递 · RETURN900001', { exact: true }).waitFor()
  check('editing return prefills saved values and cancelling does not submit')
  await page.getByRole('button', { name: '修改退回物流', exact: true }).click()
  await page.getByLabel('运单号', { exact: true }).fill('RETURN900002')
  returnBusy = true
  const returnErrors = errors.length
  await page.getByRole('button', { name: '保存退回物流', exact: true }).click()
  await page.getByRole('alert').filter({ hasText: '物流暂未保存' }).waitFor()
  assert.equal(await page.getByLabel('运单号', { exact: true }).inputValue(), 'RETURN900002')
  assert.equal(funds.afterSale.returnTrackingNo, 'RETURN900001')
  for (const error of errors.splice(returnErrors)) assert.match(error, /^Failed to load resource:.*status of 503/)
  returnBusy = false
  returnDelay = 350
  await page.getByRole('button', { name: '保存退回物流', exact: true }).dblclick()
  await page.getByRole('button', { name: '修改退回物流', exact: true }).waitFor()
  assert.equal(returnRequests, 3)
  returnDelay = 0
  await page.reload()
  await page.getByRole('heading', { name: '待店铺确认收货', exact: true }).waitFor()
  assert.equal(await page.getByLabel('运单号', { exact: true }).count(), 0)
  await page.getByText('退回物流：测试退回快递 · RETURN900002', { exact: true }).waitFor()
  check('failed return preserves draft; retry submits once and survives reload')
  await page.setViewportSize({ width: 390, height: 844 })
  await page.locator('.sale-progress').screenshot({ path: path.join(out, 'return-recorded-mobile.png') })
  assert((await page.evaluate(() => document.documentElement.scrollWidth)) <= 391)
  await page.setViewportSize({ width: 1440, height: 1000 })
  check('saved return confirmation is readable on mobile without overflow')
  await admin.reload()
  await admin.getByRole('button', { name: '确认收到退回商品' }).click()
  await admin.getByRole('button', { name: '确认', exact: true }).click()
  await admin
    .getByRole('heading', { name: '待退款核对', exact: true })
    .waitFor()
  if (funds.refund) throw Error('Receiving auto refunded')
  check('receipt progresses without automatic refund')
  order.status = 4
  order.statusName = '交易成功'
  order.completedAt = at
  funds.afterSale = null
  funds.refund = null
  funds.reviews = []
  await page.reload()
  await page.getByRole('button', { name: '分享体验' }).click()
  const xss = '<img src="x" onerror="window.__xssTriggered=true">'
  await page.getByLabel('你的体验', { exact: true }).fill(xss)
  await page.getByRole('button', { name: '提交评价' }).click()
  await page.getByText(xss, { exact: true }).waitFor()
  await go(page, origin, 'http://127.0.0.1:15183/products/1001')
  await page.getByText(xss, { exact: true }).waitFor()
  if (await page.evaluate(() => window.__xssTriggered))
    throw Error('Review executed HTML')
  check('completed-item review renders as plain text')
  await go(admin, adminOrigin, 'http://127.0.0.1:15184/finance')
  await admin.getByText('-¥129.00', { exact: true }).waitFor()
  await admin.screenshot({
    path: path.join(out, 'admin-finance-contract.png'),
    fullPage: true,
  })
  check('finance shows independent totals and negative daily net')
  await page.setViewportSize({ width: 390, height: 844 })
  await go(page, origin, `http://127.0.0.1:15183/orders/${id}`)
  await page
    .getByRole('heading', { name: '你的评价 · 5 / 5', exact: true })
    .waitFor()
  await page.screenshot({
    path: path.join(out, 'buyer-order-mobile-contract.png'),
    fullPage: true,
  })
  if ((await page.evaluate(() => document.documentElement.scrollWidth)) > 391)
    throw Error('Buyer support mobile overflow')
  await admin.setViewportSize({ width: 390, height: 844 })
  await admin.screenshot({
    path: path.join(out, 'admin-finance-mobile-contract.png'),
    fullPage: true,
  })
  if ((await admin.evaluate(() => document.documentElement.scrollWidth)) > 391)
    throw Error(
      'Finance mobile overflow ' +
        JSON.stringify(
          await admin.evaluate(() =>
            Array.from(document.querySelectorAll('body *'))
              .map((e) => ({
                tag: e.tagName,
                cls: e.className,
                left: e.getBoundingClientRect().left,
                right: e.getBoundingClientRect().right,
                width: e.getBoundingClientRect().width,
              }))
              .filter((e) => e.right > innerWidth + 1 && e.width > 0)
              .slice(0, 16),
          ),
        ),
    )
  check('new buyer support and finance mobile layouts')
  paymentConfigured = false
  order.status = 0
  order.statusName = '待支付'
  funds.payment = null
  funds.afterSale = null
  funds.refund = null
  await go(page, origin, `http://127.0.0.1:15183/payment/${id}`)
  await page.getByText('支付暂未开放。', { exact: false }).waitFor()
  if (await page.getByRole('button', { name: '前往支付宝沙箱' }).count())
    throw Error('Unavailable payment link present')
  check('missing configuration stays unavailable')
  if (errors.length) throw Error(JSON.stringify(errors))
  fs.writeFileSync(
    path.join(out, 'result.json'),
    JSON.stringify(
      {
        kind: 'UI contract fixtures; not bank or DB funds evidence',
        checks,
        errors,
      },
      null,
      2,
    ),
  )
  console.log('UI fixture checks', checks.length)
  await context.unroute('https://openapi-sandbox.dl.alipaydev.com/**')
  await context.unroute('**/api/v1/**')
  return checks
}
module.exports = { checkSettlementContract }
