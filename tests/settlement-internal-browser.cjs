// Invoked only by SettlementBrowserCheck against an isolated *_it database.
// Real buyer/admin UI and real HTTP/MySQL; the Java PaymentGateway is the sole double.
const { chromium } = require('playwright')
const http = require('node:http'),
  fs = require('node:fs'),
  path = require('node:path'),
  assert = require('node:assert/strict')
const root = path.resolve(__dirname, '..'),
  apiOrigin = new URL(process.env.SCENT_INTERNAL_API)
assert(
  ['127.0.0.1', 'localhost'].includes(apiOrigin.hostname) &&
    apiOrigin.protocol === 'http:',
)
const output = process.env.SCENT_INTERNAL_BROWSER_EVIDENCE,
  control = process.env.SCENT_INTERNAL_CONTROL,
  product = Number(process.env.SCENT_INTERNAL_PRODUCT)
assert(output && control && Number.isSafeInteger(product))
const password = 'V1TestPass123!',
  checks = [],
  errors = [],
  receipts = {},
  orders = []
const mark = (name) => {
  checks.push(name)
  console.log('PASS ' + name)
}
const server = http.createServer((req, res) => {
  if (req.url.startsWith('/api/') || req.url.startsWith('/media/')) {
    const proxy = http.request(
      new URL(req.url, apiOrigin),
      { method: req.method, headers: { ...req.headers, host: apiOrigin.host } },
      (upstream) => {
        res.writeHead(upstream.statusCode, upstream.headers)
        upstream.pipe(res)
      },
    )
    proxy.on('error', () => {
      res.writeHead(502)
      res.end('Isolated proxy unavailable')
    })
    req.pipe(proxy)
    return
  }
  const pathname = new URL(req.url, 'http://localhost').pathname,
    admin = pathname.startsWith('/admin/'),
    base = path.join(root, 'apps', admin ? 'admin' : 'storefront', 'dist')
  let file = path.resolve(base, admin ? pathname.slice(7) : pathname.slice(1))
  if (!file.startsWith(base + path.sep) && file !== base) {
    res.writeHead(400)
    res.end()
    return
  }
  if (!fs.existsSync(file) || !fs.statSync(file).isFile())
    file = path.join(base, 'index.html')
  const types = {
    '.html': 'text/html; charset=utf-8',
    '.js': 'text/javascript',
    '.css': 'text/css',
    '.png': 'image/png',
    '.webp': 'image/webp',
    '.woff2': 'font/woff2',
    '.svg': 'image/svg+xml',
  }
  res.writeHead(200, {
    'Content-Type': types[path.extname(file)] || 'application/octet-stream',
  })
  fs.createReadStream(file).pipe(res)
})
;(async () => {
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  const origin = 'http://127.0.0.1:' + server.address().port
  const api = async (route, token, body) => {
    const r = await fetch(origin + '/api/v1' + route, {
      method: body === undefined ? 'GET' : 'POST',
      headers: {
        ...(token ? { Authorization: 'Bearer ' + token } : {}),
        ...(body ? { 'Content-Type': 'application/json' } : {}),
      },
      body: body ? JSON.stringify(body) : undefined,
    })
    const j = await r.json()
    assert(r.ok && j.resultCode < 400, route + ' ' + j.errorCode)
    return j.data
  }
  const adminSession = await api('/auth/admin-login', null, {
      loginName: 'integration-admin',
      password,
    }),
    buyerSession = await api('/auth/login', null, {
      loginName: process.env.SCENT_INTERNAL_BUYER,
      password,
    })
  const day = new Intl.DateTimeFormat('en-CA', {
      timeZone: 'Asia/Shanghai',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
    }).format(new Date()),
    financePath = '/admin/finance?from=' + day + '&to=' + day
  const before = await api(financePath, adminSession.token)
  const browser = await chromium.launch({
      headless: true,
      ...(process.env.SCENT_BROWSER_CHANNEL
        ? { channel: process.env.SCENT_BROWSER_CHANNEL }
        : {}),
    }),
    context = await browser.newContext({
      viewport: { width: 1440, height: 1000 },
      locale: 'zh-CN',
      reducedMotion: 'reduce',
    })
  const buyer = await context.newPage(),
    admin = await context.newPage()
  for (const p of [buyer, admin]) {
    p.on('pageerror', (e) => errors.push(e.message))
    p.on('console', (m) => {
      if (m.type() === 'error') errors.push(m.text() + ' @ ' + m.location().url)
    })
    p.on('response', (response) => {
      if (response.status() >= 400)
        errors.push('HTTP ' + response.status() + ' ' + new URL(response.url()).pathname)
    })
    p.on('dialog', (d) => d.accept())
  }
  let currentOrder
  await context.route(
    'https://openapi-sandbox.dl.alipaydev.com/**',
    async (route) => {
      assert.equal(route.request().headers().authorization, undefined)
      assert.equal(
        new URL(route.request().url()).searchParams.get('internal_order'),
        currentOrder.orderNo,
      )
      await route.fulfill({
        contentType: 'text/html',
        body: '<!doctype html><meta charset="utf-8"><title>隔离支付替身</title><h1>隔离支付替身 · 不连接支付宝</h1><button>确认隔离回执</button>',
      })
    },
  )
  const snap = async (p, name) => {
    await p.evaluate(async () => {
      document.activeElement?.blur()
      window.scrollTo({ top: 0, behavior: 'instant' })
      await new Promise((resolve) =>
        requestAnimationFrame(() => requestAnimationFrame(resolve)),
      )
      await document.fonts.ready
      await Promise.allSettled(
        document
          .getAnimations()
          .filter(
            (a) =>
              a.playState === 'running' &&
              a.effect?.getComputedTiming().iterations !== Infinity,
          )
          .map((a) => a.finished),
      )
      await Promise.allSettled([...document.images].map((i) => i.decode()))
    })
    await p.screenshot({
      path: path.join(output, name + '.png'),
      fullPage: true,
    })
  }
  const stock = async () =>
    (await api('/catalog/products/' + product)).product.stock
  const createPaid = async (name) => {
    await buyer.goto(origin + '/products/' + product)
    const added = buyer.waitForResponse(
      (r) =>
        new URL(r.url()).pathname === '/api/v1/cart' &&
        r.request().method() === 'POST',
    )
    await buyer.getByRole('button', { name: /^加入购物车/ }).click()
    await added
    await buyer.goto(origin + '/cart')
    await buyer.getByRole('link', { name: '前往结算', exact: true }).click()
    await buyer.getByRole('button', { name: '确认下单', exact: true }).click()
    await buyer.waitForURL('**/payment/*')
    const id = Number(new URL(buyer.url()).pathname.split('/').pop()),
      detail = await api('/orders/' + id, buyerSession.token)
    currentOrder = detail.order
    assert.equal(currentOrder.totalCents, 12900)
    orders.push({ case: name, id, orderNo: currentOrder.orderNo })
    await buyer
      .getByRole('button', { name: '前往支付宝沙箱', exact: true })
      .click()
    await buyer.waitForURL('https://openapi-sandbox.dl.alipaydev.com/**')
    await buyer
      .getByRole('button', { name: '确认隔离回执', exact: true })
      .click()
    receipts[currentOrder.orderNo] = {
      cents: 12900,
      paidAt: new Date(Math.floor(Date.now() / 1000) * 1000).toISOString(),
    }
    fs.writeFileSync(control + '.tmp', JSON.stringify(receipts))
    fs.renameSync(control + '.tmp', control)
    await buyer.goto(origin + '/payment/' + id + '?returned=1')
    await buyer.getByRole('region', { name: '付款成功', exact: true }).waitFor()
    assert.equal(
      (await api('/orders/' + id + '/settlement', buyerSession.token)).payment
        .status,
      'SUCCESS',
    )
    mark(name + ': real checkout and query commit one confirmed payment')
    return id
  }
  const ship = async (id) => {
    await admin.goto(origin + '/admin/orders')
    const row = admin
      .locator('.el-table__row')
      .filter({ hasText: orders.find((x) => x.id === id).orderNo })
    await row.getByRole('button', { name: '查看处理', exact: true }).click()
    await admin.getByRole('button', { name: '开始配货', exact: true }).click()
    await admin
      .getByRole('button', { name: '开始配货', exact: true })
      .waitFor({ state: 'hidden' })
    await admin
      .getByRole('button', { name: '填写发货信息', exact: true })
      .click()
    await admin.getByLabel('承运方', { exact: true }).fill('隔离虚拟物流')
    await admin.getByLabel('运单号', { exact: true }).fill('INTERNAL-' + id)
    const sent = admin.waitForResponse(
      (r) => new URL(r.url()).pathname === `/api/v1/admin/orders/${id}/ship`,
    )
    await admin.getByRole('button', { name: '确认发货', exact: true }).click()
    await sent
    assert.equal(
      (await api('/orders/' + id, buyerSession.token)).order.status,
      3,
    )
  }
  try {
    await buyer.goto(origin + '/login')
    await buyer
      .getByLabel('手机号', { exact: true })
      .fill(process.env.SCENT_INTERNAL_BUYER)
    await buyer.getByLabel('密码', { exact: true }).fill(password)
    await buyer.getByRole('button', { name: '登录', exact: true }).click()
    await buyer.waitForURL('**/account')
    await admin.goto(origin + '/admin/login')
    await admin.getByLabel('账号', { exact: true }).fill('integration-admin')
    await admin.getByLabel('密码', { exact: true }).fill(password)
    await admin.getByRole('button', { name: '登录', exact: true }).click()
    await admin
      .getByRole('heading', { name: '商品管理', exact: true })
      .waitFor()
    const normal = await createPaid('normal')
    await ship(normal)
    await buyer.goto(origin + '/orders/' + normal)
    await buyer.getByRole('button', { name: '确认收货', exact: true }).click()
    await buyer.getByRole('button', { name: '分享体验', exact: true }).click()
    await buyer
      .getByLabel('你的体验', { exact: true })
      .fill('[隔离端到端] 页面、HTTP、MySQL一致性验收。')
    await buyer.getByRole('button', { name: '提交评价', exact: true }).click()
    await buyer
      .getByRole('heading', { name: '你的评价 · 5 / 5', exact: true })
      .waitFor()
    assert.equal(
      (await api('/orders/' + normal + '/settlement', buyerSession.token))
        .reviews.length,
      1,
    )
    await snap(buyer, 'normal-completed-review')
    mark('normal: admin pack/ship, buyer receive and one persisted review')
    for (const name of ['unshipped', 'returned']) {
      const id = await createPaid(name)
      if (name === 'returned') await ship(id)
      await buyer.goto(origin + '/orders/' + id)
      await buyer
        .getByRole('button', { name: '申请整单售后', exact: true })
        .click()
      await buyer
        .getByLabel('申请原因', { exact: true })
        .fill('[隔离端到端] ' + name)
      await buyer.getByRole('button', { name: '提交申请', exact: true }).click()
      await buyer
        .getByRole('heading', { name: '待审核', exact: true })
        .waitFor()
      await admin.goto(origin + '/admin/after-sales?orderId=' + id)
      await admin
        .getByLabel('审核说明', { exact: true })
        .fill('隔离虚拟履约验收；同意整单处理')
      await admin
        .getByRole('button', { name: '同意整单售后', exact: true })
        .click()
      if (name === 'returned') {
        await admin
          .getByRole('heading', { name: '待退回商品', exact: true })
          .waitFor()
        await buyer.reload()
        await buyer.getByLabel('承运方', { exact: true }).fill('隔离虚拟退回')
        await buyer.getByLabel('运单号', { exact: true }).fill('RETURN-' + id)
        await buyer
          .getByRole('button', { name: '保存退回物流', exact: true })
          .click()
        await buyer
          .getByText('退回物流：隔离虚拟退回 · RETURN-' + id, { exact: true })
          .waitFor()
        await admin.reload()
        await admin
          .getByRole('button', { name: '确认收到退回商品', exact: true })
          .click()
        await admin.getByRole('button', { name: '确认', exact: true }).click()
      }
      await admin
        .getByRole('heading', { name: '待退款核对', exact: true })
        .waitFor()
      const reserved = await stock()
      await admin
        .getByRole('button', { name: '核对并处理整单退款', exact: true })
        .click()
      await admin.getByRole('button', { name: '确认', exact: true }).click()
      await admin
        .getByRole('heading', { name: '整单已退款', exact: true })
        .waitFor()
      assert.equal(await stock(), reserved)
      await admin
        .getByLabel('可售入库原因', { exact: true })
        .fill('隔离虚拟退回，核实可售入库一次')
      await admin
        .getByRole('button', { name: '确认可售并入库', exact: true })
        .click()
      await admin.getByRole('button', { name: '确认', exact: true }).click()
      await admin.getByText('已恢复一次', { exact: true }).waitFor()
      assert.equal(await stock(), reserved + 1)
      await buyer.reload()
      await buyer
        .getByRole('heading', { name: '整单已退款', exact: true })
        .waitFor()
      assert.equal(
        await buyer
          .getByRole('button', { name: '分享体验', exact: true })
          .count(),
        0,
      )
      await snap(admin, name + '-refund-restocked')
      mark(
        name +
          ': buyer/admin whole-order refund persists and restock stays separate',
      )
    }
    const after = await api(financePath, adminSession.token)
    assert.equal(after.paidCents - before.paidCents, 38700)
    assert.equal(after.refundedCents - before.refundedCents, 25800)
    assert.equal(after.netCents - before.netCents, 12900)
    assert.equal(await stock(), 7)
    await admin.goto(origin + '/admin/finance')
    await admin
      .getByRole('heading', { name: '成功日账目', exact: true })
      .waitFor()
    await snap(admin, 'real-http-finance')
    mark('finance: three receipts, two refunds, one net sale match MySQL stock')
    await buyer.setViewportSize({ width: 390, height: 844 })
    await buyer.goto(origin + '/orders/' + normal)
    await buyer
      .getByRole('heading', { name: '你的评价 · 5 / 5', exact: true })
      .waitFor()
    assert(
      (await buyer.evaluate(() => document.documentElement.scrollWidth)) <= 391,
    )
    await snap(buyer, 'completed-review-mobile')
    mark('real completed order remains readable on mobile')
    assert.deepEqual(errors, [])
    fs.writeFileSync(
      path.join(output, 'result.json'),
      JSON.stringify(
        {
          kind: 'real browser/HTTP/MySQL with Java PaymentGateway test double',
          checks,
          errors,
          orders,
          financeDelta: {
            paidCents: 38700,
            refundedCents: 25800,
            netCents: 12900,
          },
          stock: 7,
          externalBankRequests: 0,
        },
        null,
        2,
      ),
    )
  } catch (e) {
    console.error(e.stack || e)
    for (const [p, label] of [
      [buyer, 'buyer'],
      [admin, 'admin'],
    ])
      try {
        await p.screenshot({
          path: path.join(output, 'failure-' + label + '.png'),
          timeout: 5000,
        })
      } catch {}
    throw e
  } finally {
    await browser.close()
    await new Promise((resolve) => server.close(resolve))
  }
})().catch((e) => {
  console.error(e)
  server.close()
  process.exit(1)
})
