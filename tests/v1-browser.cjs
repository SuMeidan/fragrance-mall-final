// Real local HTTP/browser flow. Creates fictional records and hides its own QA product afterwards.
const { chromium } = require('playwright')
const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert/strict')
const {
  checkMemberMotion,
  checkMemberImageGestures,
} = require('./member-motion-browser.cjs')
const { checkAdvisorStreaming } = require('./advisor-stream-browser.cjs')
const { checkStorefrontQuality } = require('./storefront-quality-browser.cjs')
const { checkSettlementContract } = require('./settlement-contract-browser.cjs')
const { checkAdminDesign } = require('./admin-design-browser.cjs')
const root = path.resolve(__dirname, '..')
const configFile = path.join(root, 'runtime/v1/config.json')
const config = fs.existsSync(configFile)
  ? JSON.parse(fs.readFileSync(configFile, 'utf8'))
  : {}
const origin = new URL(
  process.env.SCENT_BROWSER_ORIGIN || 'http://127.0.0.1:15185',
)
const adminOrigin = new URL(
  process.env.SCENT_ADMIN_ORIGIN || new URL('/admin/', origin),
)
for (const url of [origin, adminOrigin]) {
  assert(
    ['127.0.0.1', 'localhost'].includes(url.hostname) &&
      url.protocol === 'http:',
    'Only local isolated targets are allowed',
  )
}
const output =
  process.env.SCENT_BROWSER_EVIDENCE ||
  path.join(
    config.runtimeDir || path.join(root, 'runtime/v1'),
    'browser-evidence',
  )
fs.mkdirSync(output, { recursive: true })
const checks = [],
  errors = []
const mark = (name) => {
  checks.push(name)
  console.log('PASS ' + name)
}
async function api(
  route,
  token,
  body,
  method = body === undefined ? 'GET' : 'POST',
) {
  const response = await fetch(new URL('/api/v1' + route, origin), {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: 'Bearer ' + token } : {}),
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  })
  const value = await response.json()
  assert(
    response.ok && value.resultCode < 400,
    'Local API failed ' + method + ' ' + route + ' ' + value.errorCode,
  )
  return value.data
}
;(async () => {
  const login = await api('/auth/admin-login', null, {
    loginName: process.env.SCENT_ADMIN_LOGIN || config.adminLogin,
    password: process.env.SCENT_ADMIN_PASSWORD || config.adminPassword,
  })
  const adminToken = login.token
  const categories = await api('/catalog/categories'),
    families = await api('/catalog/families')
  const category = categories
    .flatMap((c) => c.children)
    .find((c) => c.inputView === 'SCENT')
  assert(category && families.length === 6)
  const name = '浏览器验证-' + Date.now()
  const input = {
    categoryId: category.id,
    familyId: families[0].id,
    name,
    subtitle: '虚构验证品，结束后下架',
    spec: '180g',
    unit: '件',
    priceCents: 12900,
    initialStock: 5,
    active: true,
    imagePath: '/assets/candle.png',
    description:
      '虚构验证品，结束后下架\n气味与风格：虚构验证品，结束后下架\n选择建议：适合偏爱上述气味方向的人；明确排斥该香调者不选。\n仅用于本地工程验证\nSCENT虚构教学商品；仅用于测试。',
    usageNotes: '按说明使用',
    compatibleIds: [],
  }
  const product = (await api('/admin/products', adminToken, input)).product
  async function hideProduct() {
    const current = (await api('/admin/products/' + product.id, adminToken))
      .product
    const edit = {
      ...input,
      active: false,
      version: current.version,
      imagePath: current.imagePath,
    }
    delete edit.initialStock
    await api('/admin/products/' + product.id, adminToken, edit, 'PUT')
  }
  const browser = await chromium
    .launch({
      headless: true,
      ...(process.env.SCENT_BROWSER_CHANNEL
        ? { channel: process.env.SCENT_BROWSER_CHANNEL }
        : {}),
    })
    .catch(async (error) => {
      await hideProduct()
      throw error
    })
  const context = await browser.newContext({
    viewport: { width: 1440, height: 1000 },
    locale: 'zh-CN',
    reducedMotion: 'reduce',
  })
  const page = await context.newPage()
  const listen = (p) => {
    p.on('pageerror', (e) => errors.push(e.message))
    p.on('console', (m) => {
      if (m.type() === 'error') errors.push(m.text())
    })
    p.on('dialog', (d) => d.accept())
  }
  listen(page)
  try {
    await page.goto(origin.href)
    await page.locator('.experience-brand').waitFor()
    await page.screenshot({
      path: path.join(output, 'home.png'),
      fullPage: true,
    })
    mark('Built homepage renders')
    await checkMemberMotion(page, origin, output, mark)
    await checkStorefrontQuality(page, origin, output, mark, product)
    await page.goto(new URL('/products/' + product.id, origin).href)
    await page.getByRole('button', { name: /^加入购物车/ }).click()
    await page.waitForURL('**/login?**')
    await page.getByRole('link', { name: '创建账号', exact: true }).click()
    const phone =
      '188' + String(Math.floor(Math.random() * 100000000)).padStart(8, '0')
    const password =
      'BrowserFixture' + Math.random().toString(36).slice(2) + '!'
    await page.getByLabel('称呼', { exact: true }).fill('浏览器虚构买家')
    await page.getByLabel('手机号', { exact: true }).fill(phone)
    await page.getByLabel('密码', { exact: true }).fill(password)
    await page
      .getByRole('button', { name: '创建账号并登录', exact: true })
      .click()
    await page.waitForURL('**/products/' + product.id)
    await page.getByRole('status').filter({ hasText: '已加入购物车' }).waitFor()
    mark('Register/login returns to product and adds once')
    assert.equal(
      (await page
        .locator('.site-header')
        .getByRole('button', { name: /购物[袋车]/ })
        .count()) +
        (await page
          .locator('.site-header')
          .getByRole('link', { name: /购物[袋车]/ })
          .count()),
      1,
    )
    await page
      .locator('.site-header')
      .getByRole('link', { name: '购物袋', exact: true })
      .click()
    await page
      .getByRole('heading', { name: product.name, exact: true })
      .waitFor()
    await page
      .locator('.order-summary')
      .getByText('¥129.00', { exact: true })
      .first()
      .waitFor()
    await page.screenshot({ path: path.join(output, 'refined-cart.png') })
    mark(
      'Single bag navigation opens the full real cart with the same server amount',
    )
    await page
      .getByRole('button', { name: /增加.*数量/ })
      .first()
      .click()
    await page.getByRole('link', { name: '前往结算' }).click()
    await page.getByRole('button', { name: '＋ 添加地址' }).click()
    for (const [label, value] of [
      ['收货人', '浏览器虚构买家'],
      ['联系电话', phone],
      ['省市区', '虚构省 / 虚构市'],
      ['详细地址', '仅隔离测试，不真实发货'],
    ])
      await page.getByLabel(label, { exact: true }).fill(value)
    await page.getByRole('button', { name: '保存地址', exact: true }).click()
    await page
      .getByRole('heading', { name: '新建地址' })
      .waitFor({ state: 'hidden' })
    await page.screenshot({ path: path.join(output, 'refined-checkout.png') })
    await page.getByRole('button', { name: '确认下单' }).click()
    await page.waitForURL('**/payment/*')
    const orderId = Number(new URL(page.url()).pathname.split('/').pop())
    const caps = await api('/capabilities')
    if (!caps.paymentConfigured)
      await page.getByText('支付暂未开放。', { exact: false }).waitFor()
    await page.screenshot({ path: path.join(output, 'refined-payment.png') })
    const order = (await api('/admin/orders/' + orderId, adminToken)).order
    assert.equal(order.totalCents, 25800)
    assert.equal(order.status, 0)
    mark('Real checkout snapshots amount 25800 cents; no fake payment')
    await page.getByRole('link', { name: '查看订单详情' }).click()
    await page.getByRole('button', { name: '取消订单', exact: true }).click()
    await page.getByRole('heading', { name: '手动关闭', exact: true }).waitFor()
    assert.equal(
      (await api('/catalog/products/' + product.id)).product.stock,
      5,
    )
    mark('Cancel restores reserved stock once')
    await page.goto(new URL('/account', origin).href)
    await page
      .locator('.recent-order')
      .filter({ hasText: order.orderNo })
      .waitFor()
    assert((await page.locator('.recent-order').count()) <= 2)
    assert.equal(await page.locator('.account-layout .address-book').count(), 0)
    await page.screenshot({ path: path.join(output, 'refined-account.png') })
    const emptyTools = '**/api/v1/catalog/products?**'
    const toolFallback = (route) => {
      if (new URL(route.request().url()).searchParams.get('categoryId') === '4')
        return route.fulfill({
          json: {
            resultCode: 200,
            data: { items: [], total: 0, page: 1, pageSize: 40 },
          },
        })
      return route.continue()
    }
    await page.route(emptyTools, toolFallback)
    await page.reload()
    await page
      .locator('.recent-order')
      .filter({ hasText: order.orderNo })
      .waitFor()
    await page.waitForFunction(() => {
      const image = document.querySelector('.account-greeting img')
      return (
        image.complete &&
        image.naturalWidth > 0 &&
        /\/hero(?:-[^/]+)?\.webp$/.test(new URL(image.src).pathname)
      )
    })
    await page.unroute(emptyTools, toolFallback)
    mark(
      'Account greeting uses available category data and a decoded brand-image fallback when the tools catalog is empty',
    )
    await page.getByRole('link', { name: '收货地址', exact: true }).click()
    await page.getByText(phone, { exact: false }).first().waitFor()
    await page.screenshot({ path: path.join(output, 'refined-addresses.png') })
    mark(
      'Canvas account overview shows real recent order snapshots; dedicated address management preserves the saved buyer address',
    )
    const admin = await context.newPage()
    listen(admin)
    await admin.goto(adminOrigin.href)
    await admin
      .getByLabel('账号', { exact: true })
      .fill(process.env.SCENT_ADMIN_LOGIN || config.adminLogin)
    await admin
      .getByLabel('密码', { exact: true })
      .fill(process.env.SCENT_ADMIN_PASSWORD || config.adminPassword)
    await admin.getByRole('button', { name: '登录', exact: true }).click()
    await admin
      .getByRole('heading', { name: '商品管理', exact: true })
      .waitFor()
    await checkAdminDesign({
      admin,
      context,
      adminOrigin,
      output,
      mark,
      api,
      adminToken,
    })
    // A merchant must be able to author all four forms through the real UI.
    const authored = []
    const authorTag = '录入验证-' + Date.now()
    const description =
      '清爽的柑橘与绿叶，延续SCENT克制、留白的表达。\n气味：果皮与绿叶。甜度低，浓淡中等。\n适合少甜偏好；不适合排斥柑橘者。虚构界面验证商品。'
    async function choose(id, label) {
      const select = admin.locator(`label[for="${id}"] .el-select__wrapper`)
      const width = await select.evaluate((element) => ({
        inner: element.getBoundingClientRect().width,
        outer: element.closest('.el-select').getBoundingClientRect().width,
      }))
      assert(
        width.inner >= width.outer * 0.95,
        'Merchant can read the full selection control',
      )
      await select.click()
      await admin.getByRole('option', { name: label, exact: true }).click()
    }
    async function hideAuthored(id) {
      const detail = await api('/admin/products/' + id, adminToken)
      const fields = [
        'categoryId',
        'familyId',
        'name',
        'subtitle',
        'spec',
        'unit',
        'priceCents',
        'imagePath',
        'description',
        'usageNotes',
        'material',
        'powerInfo',
        'contents',
      ]
      await api(
        '/admin/products/' + id,
        adminToken,
        {
          ...Object.fromEntries(
            fields.map((key) => [key, detail.product[key]]),
          ),
          active: false,
          version: detail.product.version,
          compatibleIds: detail.compatibleProducts.map((p) => p.id),
        },
        'PUT',
      )
    }
    try {
      for (const [index, code] of ['P03', 'P11', 'P15', 'P18'].entries()) {
        const root = categories.find((c) =>
          c.children.some((leaf) => leaf.code === code),
        )
        const leaf = root.children.find((c) => c.code === code)
        const authoredName = `${authorTag}-${index}`
        await admin
          .getByRole('button', { name: '新增商品', exact: true })
          .click()
        await admin
          .getByRole('dialog', { name: '新增商品', exact: true })
          .waitFor()
        await choose('product-rootId', root.name)
        await choose('product-categoryId', leaf.name)
        await admin.locator('#product-name').fill(authoredName)
        if (['SCENT', 'REFILL'].includes(leaf.inputView))
          await choose('product-familyId', families[3].name)
        else assert(await admin.locator('#product-familyId').isDisabled())
        await admin.locator('#product-spec').fill('固定规格 / 1件')
        await admin.locator('#product-price').fill('89.50')
        await admin.locator('#product-initialStock').fill('3')
        await admin
          .locator('#product-subtitle')
          .fill('真实录入检查，结束后下架')
        await choose('product-imagePath', `${leaf.name}（形态示意）`)
        await admin.locator('#product-description').fill(description)
        await admin
          .locator('#product-usageNotes')
          .fill('按固定规格使用；不推断适配或疗效。')
        if (leaf.inputView === 'TOOL')
          await admin.locator('#product-material').fill('陶瓷 / 测试固定规格')
        if (leaf.inputView === 'GIFT')
          await admin
            .locator('#product-contents')
            .fill('固定预装验证品×1，独立整盒库存。')
        await admin.screenshot({
          path: path.join(output, `admin-editor-${leaf.inputView}.png`),
        })
        await admin
          .getByRole('button', { name: '保存商品', exact: true })
          .click()
        await admin.getByRole('dialog').waitFor({ state: 'hidden' })
        const result = await api(
          '/admin/products?keyword=' + encodeURIComponent(authoredName),
          adminToken,
        )
        const created = result.items.find((p) => p.name === authoredName)
        assert(created, 'UI-created product is persisted')
        authored.push(created.id)
        const detail = (await api('/catalog/products/' + created.id)).product
        assert.equal(detail.priceCents, 8950)
        assert.equal(detail.stock, 3)
        assert.equal(detail.description, description)
        assert.equal(detail.inputView, leaf.inputView)
        if (['TOOL', 'GIFT'].includes(leaf.inputView))
          assert.equal(detail.familyId, null)
        await page.goto(new URL('/products/' + created.id, origin).href)
        await page
          .getByRole('heading', { name: authoredName, exact: true })
          .waitFor()
        assert.equal(
          (await page.locator('.description').innerText()).replace(
            /\n{2,}/g,
            '\n',
          ),
          description,
        )
        if (index === 0) {
          await page.screenshot({
            path: path.join(output, 'merchant-created-product.png'),
            fullPage: true,
          })
          const editorUrl = new URL(
            `/api/v1/admin/products/${created.id}`,
            adminOrigin,
          ).href
          let releaseLoad
          const loadGate = new Promise((resolve) => {
            releaseLoad = resolve
          })
          let pendingLoad
          await admin.route(editorUrl, (route) => {
            pendingLoad = loadGate.then(() => route.continue())
            return pendingLoad
          })
          try {
            await admin
              .locator('.el-table__row')
              .filter({ hasText: authoredName })
              .getByRole('button', { name: '编辑', exact: true })
              .click()
            await admin
              .getByText('正在加载商品资料…', { exact: true })
              .waitFor()
            assert.equal(
              await admin.locator('#product-description').count(),
              0,
              'Editing fields stay absent while product details are loading',
            )
          } finally {
            releaseLoad()
            if (pendingLoad) await pendingLoad
            await admin.unroute(editorUrl)
          }
          await admin
            .locator('#product-description')
            .fill(description + '\n补充：商家更新的说明即时生效。')
          await admin
            .getByRole('button', { name: '保存商品', exact: true })
            .click()
          await admin.getByRole('dialog').waitFor({ state: 'hidden' })
          assert.match(
            (await api('/catalog/products/' + created.id)).product.description,
            /即时生效/,
          )
        }
      }
      mark(
        'Merchant creates SCENT/TOOL/REFILL/GIFT through UI; cents, description, edit and public detail persist',
      )
    } finally {
      for (const id of authored) await hideAuthored(id)
    }
    await admin
      .locator('.el-table__row')
      .filter({ hasText: name })
      .getByRole('button', { name: '编辑', exact: true })
      .click()
    await admin
      .locator('#product-upload')
      .setInputFiles(
        path.join(root, 'apps/web-shared/public/assets/candle.png'),
      )
    await admin
      .getByText('主图已上传，保存商品后生效', { exact: true })
      .waitFor()
    for (const [slot, label] of [
      ['scentImagePath', '香调意象图'],
      ['sceneImagePath', '场景横图'],
    ]) {
      await admin
        .locator(`#product-${slot}`)
        .setInputFiles(
          path.join(root, 'apps/web-shared/public/assets/candle.png'),
        )
      await admin
        .getByText(`${label}已上传，保存商品后生效`, { exact: true })
        .waitFor()
    }
    await admin.getByRole('button', { name: '保存商品', exact: true }).click()
    await admin.getByRole('dialog').waitFor({ state: 'hidden' })
    const saved = (await api('/admin/products/' + product.id, adminToken))
      .product
    assert.match(saved.imagePath, /^\/media\/[a-f0-9]{32}\.png$/)
    assert.equal((await fetch(new URL(saved.imagePath, origin))).status, 200)
    for (const field of ['scentImagePath', 'sceneImagePath']) {
      assert.match(saved[field], /^\/media\/[a-f0-9]{32}\.png$/)
      assert.equal((await fetch(new URL(saved[field], origin))).status, 200)
    }
    await page.goto(new URL('/products/' + product.id, origin).href)
    await page.getByRole('button', { name: '香调意象', exact: true }).click()
    assert(
      (await page.locator('.detail-image > img').getAttribute('src')).includes(
        saved.scentImagePath,
      ),
    )
    await page.getByRole('button', { name: '场景图', exact: true }).click()
    assert(
      (await page.locator('.detail-image > img').getAttribute('src')).includes(
        saved.sceneImagePath,
      ),
    )
    await page.locator('.product-reviews').waitFor()
    mark(
      'Three administrator images persist and switch in the real product gallery; reviews remain',
    )
    await checkMemberImageGestures(page, origin, output, mark, saved)
    mark('Administrator uploads image, saves product, public image reads')
    await admin.getByRole('link', { name: '订单', exact: true }).click()
    await admin
      .locator('.el-table__row')
      .filter({ hasText: order.orderNo })
      .getByRole('button', { name: '查看处理' })
      .click()
    await admin
      .getByText('浏览器虚构买家 · ' + phone, { exact: false })
      .waitFor()
    const orderDialog = admin.getByRole('dialog', {
      name: '订单详情',
      exact: true,
    })
    await orderDialog
      .locator('.drawer-status')
      .getByText('手动关闭', { exact: true })
      .waitFor()
    await orderDialog
      .getByRole('heading', { name: order.orderNo, exact: true })
      .waitFor()
    await orderDialog
      .locator('.amount-line')
      .filter({ hasText: '订单金额' })
      .getByText('¥258.00', { exact: true })
      .waitFor()
    mark('Administrator sees the same persisted order and address')
    for (const route of [
      '/',
      '/catalog',
      '/products/' + product.id,
      '/cart',
      '/orders',
      '/account',
      '/addresses',
    ]) {
      await page.setViewportSize({ width: 390, height: 844 })
      await page.goto(new URL(route, origin).href)
      await page.locator('main h1').first().waitFor()
      assert(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth + 1,
        ),
        'Horizontal overflow at ' + route,
      )
      await page.screenshot({
        path: path.join(
          output,
          'buyer-mobile-' + route.replaceAll('/', '_') + '.png',
        ),
      })
    }
    mark('Seven buyer mobile pages stay within viewport')
    await page.getByRole('button', { name: '香氛顾问', exact: true }).click()
    if (!caps.advisorConfigured)
      await page.getByText('香氛顾问暂未开放', { exact: false }).waitFor()
    await page.keyboard.press('Escape')
    mark('Advisor capability state and keyboard close work')
    // Controlled presentation fixture: no model calls or buyer data are sent upstream.
    const advisorRequests = []
    await page.route('**/api/v1/capabilities', (route) =>
      route.fulfill({
        json: {
          resultCode: 200,
          data: { advisorConfigured: true, paymentConfigured: false },
        },
      }),
    )
    await page.route('**/api/v1/advisor/chat/stream', (route) => {
      advisorRequests.push(route.request().postDataJSON())
      return route.fulfill({
        contentType: 'text/event-stream',
        body:
          'event: done\ndata: ' +
          JSON.stringify({
            message: [
              '### 推荐说明',
              '',
              '**展示样本**，这是受控的排版检查。',
              '',
              '- 第一条理由',
              '- 第二条理由',
              '',
              '| 项目 | 说明 |',
              '| --- | --- |',
              '| 价格 | 29元 |',
              '',
              '> 注意使用方式。',
              '',
              '`示例代码`',
              '',
              '[查看商品](/products/' + product.id + ')',
              '',
              '/products/' + product.id,
              '',
              '<img src="https://invalid.scent.test/image" onerror="window.__scentInjected=1">',
              '[危险链接](javascript:alert(1))',
              '[外部链接](https://invalid.scent.test/)',
            ].join('\n'),
          }) +
          '\n\n',
      })
    })
    await page.goto(origin.href)
    await page.getByRole('button', { name: '香氛顾问', exact: true }).click()
    await page.waitForFunction(
      () => !document.querySelector('#advisor-input').disabled,
    )
    await page.locator('#advisor-input').fill('验证排版')
    await page.getByRole('button', { name: '发送香氛咨询' }).click()
    const markdown = page.locator('.advisor-markdown')
    await markdown.getByRole('heading', { name: '推荐说明' }).waitFor()
    assert.equal(await markdown.locator('strong').innerText(), '展示样本')
    assert.equal(await markdown.locator('li').count(), 2)
    assert.equal(await markdown.locator('table tbody tr').count(), 1)
    assert.equal(await markdown.locator('blockquote').count(), 1)
    assert.equal(await markdown.locator('code').innerText(), '示例代码')
    mark(
      'Advisor Markdown renders headings, emphasis, lists, tables, quotes and code',
    )
    assert.equal(
      await markdown.locator('img,script,iframe,[onerror]').count(),
      0,
    )
    assert.equal(await page.evaluate(() => window.__scentInjected), undefined)
    assert.equal(await markdown.locator('a').count(), 2)
    assert(
      await markdown
        .locator('a')
        .evaluateAll((links) =>
          links.every((link) =>
            /^\/products\/\d+$/.test(link.getAttribute('href')),
          ),
        ),
    )
    mark(
      'Advisor Markdown blocks active HTML, images and external or executable links',
    )
    const productLinks = markdown.getByRole('link', {
      name: '查看商品',
      exact: true,
    })
    assert.equal(await productLinks.count(), 2)
    assert(
      await productLinks.evaluateAll(
        (links, target) =>
          links.every((link) => link.getAttribute('href') === target),
        '/products/' + product.id,
      ),
    )
    await productLinks.first().click()
    await page.waitForURL(new URL('/products/' + product.id, origin).href)
    await page.locator('main h1').first().waitFor()
    assert.equal(await page.locator('.advisor-message.assistant').count(), 1)
    await page.locator('#advisor-input').fill('上一款怎么用？')
    await page.getByRole('button', { name: '发送香氛咨询' }).click()
    await page.waitForFunction(
      () =>
        document.querySelectorAll('.advisor-message.assistant').length === 2,
    )
    assert.equal(
      advisorRequests[1].conversationId,
      advisorRequests[0].conversationId,
    )
    assert.equal(advisorRequests[1].turn, 2)
    assert(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth + 1,
      ),
    )
    mark('Advisor product links keep the conversation and mobile layout')
    await checkAdvisorStreaming(page, product.id, output, mark)
    await page.unroute('**/api/v1/capabilities')
    await page.unroute('**/api/v1/advisor/chat/stream')
    await checkSettlementContract({
      page,
      admin,
      context,
      origin,
      adminOrigin,
      output,
      mark,
      errors,
    })
    assert.deepEqual(errors, [])
    mark('No browser exceptions or console errors')
    await page.screenshot({
      path: path.join(output, 'mobile.png'),
      fullPage: false,
    })
    fs.writeFileSync(
      path.join(output, 'result.json'),
      JSON.stringify(
        {
          checks,
          errors,
          kind: 'Real local API/MySQL buyer/admin chain with controlled advisor streams and financial UI fixtures; no external payment/model calls',
        },
        null,
        2,
      ),
    )
  } catch (error) {
    await page
      .screenshot({ path: path.join(output, 'failure.png'), fullPage: true })
      .catch(() => {})
    fs.writeFileSync(
      path.join(output, 'failure.json'),
      JSON.stringify(
        { message: error.message, stack: error.stack, checks, errors },
        null,
        2,
      ),
    )
    throw error
  } finally {
    await browser.close()
    await hideProduct()
  }
})().catch((error) => {
  console.error(error.stack || error.message)
  process.exitCode = 1
})
