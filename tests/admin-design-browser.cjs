// Approved DAHZCL8Qxkk admin design, exercised against the isolated real API.
// This helper is only called by the local/CI regression suite, never public QA.
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

async function checkAdminDesign({
  admin,
  context,
  adminOrigin,
  output,
  mark,
  api,
  adminToken,
}) {
  const out = path.join(output, 'admin-design')
  fs.mkdirSync(out, { recursive: true })
  const url = (route) => new URL(route, adminOrigin).href
  const ready = async () => {
    await admin.locator('.workspace h1').waitFor()
    await admin.waitForLoadState('networkidle')
    await admin.locator('.el-loading-mask').waitFor({ state: 'hidden' })
    await admin.evaluate(() => document.fonts.ready)
  }
  const shot = async (name) => {
    await admin.evaluate(async () => {
      const finite = document
        .getAnimations()
        .filter(
          (a) =>
            a.playState === 'running' &&
            a.effect?.getComputedTiming().iterations !== Infinity,
        )
      await Promise.allSettled(finite.map((a) => a.finished))
    })
    return admin.screenshot({ path: path.join(out, name + '.png') })
  }
  const dimensions = []
  const guest = await context.newPage()
  try {
    await guest.setViewportSize({ width: 1440, height: 1000 })
    await guest.goto(url('login'))
    await guest.waitForFunction(() => {
      const image = document.querySelector('.login-image img')
      return image?.complete && image.naturalWidth > 0
    })
    await guest.evaluate(() => document.fonts.ready)
    await guest.screenshot({ path: path.join(out, 'login-desktop.png') })
    await guest.setViewportSize({ width: 390, height: 844 })
    assert(
      (await guest.evaluate(() => document.documentElement.scrollWidth)) <= 391,
    )
    await guest.getByLabel('账号', { exact: true }).fill('layout-only')
    await guest.getByLabel('密码', { exact: true }).focus()
    assert(
      await guest
        .getByLabel('密码', { exact: true })
        .evaluate((el) => document.activeElement === el),
    )
    await guest.screenshot({
      path: path.join(out, 'login-mobile.png'),
      fullPage: true,
    })
  } finally {
    await guest.close()
  }
  for (const viewport of [
    { width: 1440, height: 1000 },
    { width: 390, height: 844 },
  ]) {
    await admin.setViewportSize(viewport)
    for (const route of [
      'products',
      'categories',
      'orders',
      'after-sales',
      'finance',
      'accounts',
      'audits',
    ]) {
      await admin.goto(url(route))
      await ready()
      assert.equal(await admin.locator('.sidebar nav a').count(), 7)
      const width = await admin.evaluate(
        () => document.documentElement.scrollWidth,
      )
      assert(
        width <= viewport.width + 1,
        `${route} overflows ${viewport.width}`,
      )
      dimensions.push({ route, viewport, width })
      if (viewport.width === 1440) {
        assert.equal(
          Math.round((await admin.locator('.sidebar').boundingBox()).width),
          224,
        )
        assert.equal(
          await admin.evaluate(
            () => getComputedStyle(document.documentElement).backgroundColor,
          ),
          'rgb(251, 250, 247)',
        )
      }
      await shot(`${route}-${viewport.width}`)
    }
  }
  mark(
    'Approved admin login, seven workspaces and native forms render at desktop/mobile without page overflow',
  )

  await admin.setViewportSize({ width: 1440, height: 1000 })
  await admin.goto(url('products'))
  await ready()
  const categories = await api('/catalog/categories')
  const toolRoot = categories.find((root) =>
    root.children.some((c) => c.inputView === 'TOOL'),
  )
  const tool = toolRoot.children.find((c) => c.inputView === 'TOOL')
  const choose = async (id, text) => {
    await admin.locator(`label[for="${id}"] .el-select__wrapper`).click()
    await admin.getByRole('option', { name: text, exact: true }).click()
  }
  await admin.getByRole('button', { name: '新增商品', exact: true }).click()
  const editor = admin.getByRole('dialog', { name: '新增商品', exact: true })
  await editor.waitFor()
  await admin.locator('#product-name').waitFor()
  await admin.locator('label[for="product-rootId"] .el-select__wrapper').click()
  const popover = admin.locator('.el-select__popper:visible')
  assert.equal(
    await popover.evaluate((el) => getComputedStyle(el).borderRadius),
    '18px',
  )
  await shot('category-dropdown')
  await admin.getByRole('option', { name: toolRoot.name, exact: true }).click()
  await choose('product-categoryId', tool.name)
  assert(await admin.locator('#product-familyId').isDisabled())
  await admin
    .locator('label[for="product-familyId"]')
    .getByText('不适用', { exact: true })
    .waitFor()
  await shot('tool-editor')
  // A closed Element Plus combobox consumes Escape. Exercise the form's close
  // behavior from a text field, after checking the combobox independently.
  await admin.locator('#product-name').focus()
  await admin.keyboard.press('Escape')
  await editor.waitFor({ state: 'hidden' })
  assert(
    await admin
      .getByRole('button', { name: '新增商品', exact: true })
      .evaluate((el) => document.activeElement === el),
  )
  await admin.setViewportSize({ width: 390, height: 844 })
  await admin.getByRole('button', { name: '新增商品', exact: true }).click()
  await editor.waitFor()
  await admin.locator('#product-name').waitFor()
  assert((await editor.boundingBox()).width <= 391)
  await admin
    .locator('#product-description')
    .fill('手机表单可滚动输入，保留原生键盘和表单约束。')
  await shot('editor-mobile')
  await admin.getByRole('button', { name: '取消', exact: true }).click()
  await editor.waitFor({ state: 'hidden' })
  mark(
    'Rounded category popover, inapplicable scent control, editor scrolling and Escape focus return work',
  )

  await admin.setViewportSize({ width: 1440, height: 1000 })
  const findEditor = async (name, id) => {
    await admin.goto(url('products'))
    await ready()
    await admin.getByLabel('搜索商品', { exact: true }).fill(name)
    await admin.getByRole('button', { name: '查询', exact: true }).click()
    await ready()
    await admin
      .locator('.el-table__row')
      .filter({ hasText: id ? `#${id} ·` : name })
      .first()
      .getByRole('button', { name: '编辑', exact: true })
      .click()
    await admin.locator('#product-name').waitFor()
    await admin.waitForLoadState('networkidle')
  }
  const editingDialog = admin.getByRole('dialog', {
    name: '编辑商品',
    exact: true,
  })
  const scent = categories
    .flatMap((root) => root.children)
    .find((c) => c.inputView === 'SCENT')
  const scentSample = (
    await api('/admin/products?categoryId=' + scent.id, adminToken)
  ).items[0]
  assert(scentSample, 'A real SCENT product is required for the editor check')
  await findEditor(scentSample.name, scentSample.id)
  const stockPanel = editingDialog.locator('.editor-panel').filter({
    has: admin.getByRole('heading', { name: '上架与库存', exact: true }),
  })
  assert(
    (await stockPanel.boundingBox()).y +
      (await stockPanel.boundingBox()).height <=
      1000,
  )
  const thumbnails = await editingDialog
    .locator('.media-thumbnail')
    .evaluateAll((els) =>
      els.map((el) => Math.round(el.getBoundingClientRect().top)),
    )
  assert.equal(thumbnails.length, 2)
  assert.equal(thumbnails[0], thumbnails[1])
  assert(
    await editingDialog.locator('.image-materials').evaluate((el) => !el.open),
  )
  await editingDialog.locator('.image-materials summary').click()
  await editingDialog
    .locator('#product-imagePath')
    .waitFor({ state: 'visible' })
  await editingDialog.locator('.image-materials summary').click()
  await shot('compact-product-images')
  await editingDialog.getByRole('button', { name: '取消', exact: true }).click()
  const toolSample = (
    await api('/admin/products?categoryId=' + tool.id, adminToken)
  ).items[0]
  assert(toolSample, 'A real TOOL product is required for the upload check')
  const toolId = toolSample.id
  const toolBefore = (await api('/admin/products/' + toolId, adminToken))
    .product
  await findEditor(toolSample.name, toolId)
  const fileChooser = admin.waitForEvent('filechooser')
  await editingDialog
    .locator('.media-thumbnail')
    .filter({ hasText: '器具细节图' })
    .click()
  await (
    await fileChooser
  ).setFiles(
    path.join(__dirname, '../apps/web-shared/public/assets/candle.png'),
  )
  await admin
    .getByText('器具细节图已上传，保存商品后生效', { exact: true })
    .waitFor()
  await editingDialog.getByRole('button', { name: '取消', exact: true }).click()
  assert.equal(
    (await api('/admin/products/' + toolBefore.id, adminToken)).product
      .scentImagePath,
    toolBefore.scentImagePath,
  )
  mark(
    'Compact two-image editor keeps stock visible, exposes material disclosure and supports native TOOL upload without saving cancelled changes',
  )

  await admin.goto(url('products'))
  await ready()
  let signal
  const entered = new Promise((resolve) => {
    signal = resolve
  })
  const pattern = '**/api/v1/admin/products?**'
  await admin.route(pattern, async (route) => {
    if (new URL(route.request().url()).searchParams.get('keyword') !== '雾')
      return route.continue()
    const response = await route.fetch()
    signal()
    await new Promise((resolve) => setTimeout(resolve, 1200))
    return route.fulfill({ response })
  })
  try {
    const expected = await api('/admin/products', adminToken)
    const old = admin.waitForResponse(
      (response) =>
        new URL(response.url()).searchParams.get('keyword') === '雾',
    )
    await admin.getByLabel('搜索商品', { exact: true }).fill('雾')
    await admin.getByRole('button', { name: '查询', exact: true }).click()
    await entered
    await admin.getByRole('button', { name: '清空', exact: true }).click()
    await (await old).finished()
    await ready()
    assert.equal(
      await admin.getByLabel('搜索商品', { exact: true }).inputValue(),
      '',
    )
    assert.equal(
      await admin.locator('.toolbar-count').innerText(),
      `${expected.total} 件作品`,
    )
    assert.deepEqual(
      await admin.locator('.table-product strong').allTextContents(),
      expected.items.map((p) => p.name),
    )
  } finally {
    await admin.unroute(pattern)
  }
  mark(
    'A delayed previous product search cannot overwrite the newer cleared query',
  )

  const financePattern = '**/api/v1/admin/finance?**'
  await admin.route(financePattern, async (route) => {
    const response = await route.fetch(),
      body = await response.json()
    Object.assign(body.data, {
      paidCents: 987654321000,
      refundedCents: 12345678900,
      netCents: 975308642100,
    })
    return route.fulfill({ response, json: body })
  })
  try {
    for (const width of [1440, 820, 390, 320]) {
      await admin.setViewportSize({ width, height: 1000 })
      await admin.goto(url('finance'))
      await admin.locator('.finance-totals').waitFor()
      await ready()
      assert(
        (await admin.evaluate(() => document.documentElement.scrollWidth)) <=
          width,
      )
      assert(
        (await admin.locator('.finance-totals strong').first().innerText()) ===
          new Intl.NumberFormat('zh-CN', {
            style: 'currency',
            currency: 'CNY',
          }).format(9876543210),
      )
      await shot('long-finance-' + width)
    }
  } finally {
    await admin.unroute(financePattern)
  }
  await admin.setViewportSize({ width: 1440, height: 1000 })
  await admin.goto(url('products'))
  await ready()
  const contrasts = await admin.evaluate(() => {
    const luminance = (rgb) => {
      const channels = rgb
        .match(/[\d.]+/g)
        .slice(0, 3)
        .map(Number)
        .map((v) => {
          const s = v / 255
          return s <= 0.04045 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4
        })
      return channels.reduce(
        (sum, v, i) => sum + v * [0.2126, 0.7152, 0.0722][i],
        0,
      )
    }
    return [
      '.workspace-heading p',
      '.el-table th .cell',
      '.table-product small',
      '.el-tag',
    ].map((selector) => {
      const el = document.querySelector(selector)
      let parent = el,
        background
      while (parent) {
        background = getComputedStyle(parent).backgroundColor
        if (background !== 'rgba(0, 0, 0, 0)') break
        parent = parent.parentElement
      }
      const values = [
        luminance(getComputedStyle(el).color),
        luminance(background),
      ].sort((a, b) => b - a)
      return { selector, ratio: (values[0] + 0.05) / (values[1] + 0.05) }
    })
  })
  assert(
    contrasts.every((item) => item.ratio >= 4.5),
    JSON.stringify(contrasts),
  )
  mark(
    'Finance preserves long currency values at four viewport sizes; sampled helper text, table headings and status labels meet 4.5:1 contrast',
  )

  await admin.setViewportSize({ width: 1440, height: 1000 })
  await admin.goto(url('products'))
  await ready()
  const stockButton = admin
    .getByRole('button', { name: '调整库存', exact: true })
    .first()
  await stockButton.click()
  const stockDialog = admin.getByRole('dialog', {
    name: '调整库存',
    exact: true,
  })
  await stockDialog.waitFor()
  const id = Number(
    (
      await stockDialog.locator('.stock-product small').first().innerText()
    ).match(/#(\d+)/)[1],
  )
  const original = (await api('/admin/products/' + id, adminToken)).product
  try {
    await admin.locator('#stock-delta').fill('0')
    await admin.locator('#stock-reason').fill('隔离UI验证')
    assert(
      await stockDialog
        .getByRole('button', { name: '确认调整', exact: true })
        .isDisabled(),
    )
    await admin.locator('#stock-delta').fill(String(-original.stock - 1))
    assert(
      await stockDialog
        .getByRole('button', { name: '确认调整', exact: true })
        .isDisabled(),
    )
    await admin.locator('#stock-delta').fill('2')
    await shot('stock-confirmation')
    await stockDialog
      .getByRole('button', { name: '确认调整', exact: true })
      .click()
    await stockDialog.waitFor({ state: 'hidden' })
    let current = (await api('/admin/products/' + id, adminToken)).product
    assert.equal(current.stock, original.stock + 2)
    assert.equal(current.version, original.version + 1)
    await ready()
    await stockButton.click()
    await stockDialog.waitFor()
    await admin.locator('#stock-delta').fill('-2')
    await admin.locator('#stock-reason').fill('隔离UI验证恢复原库存')
    await stockDialog
      .getByRole('button', { name: '确认调整', exact: true })
      .click()
    await stockDialog.waitFor({ state: 'hidden' })
    current = (await api('/admin/products/' + id, adminToken)).product
    assert.equal(current.stock, original.stock)
    assert.equal(current.version, original.version + 2)
  } finally {
    const current = (await api('/admin/products/' + id, adminToken)).product
    if (current.stock !== original.stock)
      await api(`/admin/products/${id}/stock`, adminToken, {
        version: current.version,
        delta: original.stock - current.stock,
        reason: '恢复隔离UI验证库存',
      })
  }
  mark(
    'Inventory modal rejects invalid deltas and persists positive/negative adjustments with real version increments',
  )

  const flat = await api('/admin/categories', adminToken)
  const leaf = flat.find((c) => c.parentId && c.enabled)
  await admin.goto(url('categories'))
  await ready()
  try {
    await admin.locator(`[data-category-id="${leaf.id}"]`).click()
    await admin.locator('#category-name').fill(leaf.name + '·界面核对')
    await admin.locator('.category-editor .el-switch__core').click()
    assert(
      !(await admin
        .getByRole('switch', { name: '启用分类', exact: true })
        .isChecked()),
    )
    await admin.getByRole('button', { name: '保存修改', exact: true }).click()
    await admin.getByText('分类已保存', { exact: true }).first().waitFor()
    await ready()
    const current = (await api('/admin/categories', adminToken)).find(
      (c) => c.id === leaf.id,
    )
    assert.equal(current.name, leaf.name + '·界面核对')
    assert.equal(current.enabled, false)
    assert(
      !(await api('/catalog/categories'))
        .flatMap((c) => c.children)
        .some((c) => c.id === leaf.id),
    )
    await admin.locator('#category-name').fill(leaf.name)
    await admin.locator('.category-editor .el-switch__core').click()
    assert(
      await admin
        .getByRole('switch', { name: '启用分类', exact: true })
        .isChecked(),
    )
    const response = admin.waitForResponse(
      (r) =>
        r.url().includes('/admin/categories/' + leaf.id) &&
        r.request().method() === 'PATCH',
    )
    await admin.getByRole('button', { name: '保存修改', exact: true }).click()
    await response
    assert(
      (await api('/catalog/categories'))
        .flatMap((c) => c.children)
        .some((c) => c.id === leaf.id && c.name === leaf.name),
    )
    await shot('category-editor')
  } finally {
    const current = (await api('/admin/categories', adminToken)).find(
      (c) => c.id === leaf.id,
    )
    if (current.name !== leaf.name || current.enabled !== leaf.enabled)
      await api(
        '/admin/categories/' + leaf.id,
        adminToken,
        { name: leaf.name, sort: leaf.sort, enabled: leaf.enabled },
        'PATCH',
      )
  }
  mark(
    'Two-level category inspector saves names and visibility through the real API and restores the original category',
  )

  await admin.goto(url('accounts'))
  await ready()
  const accounts = await api('/admin/accounts', adminToken)
  for (const account of accounts.items) {
    if (/^\d{11}$/.test(account.loginName)) {
      const row = admin
        .locator('.el-table__row')
        .filter({ hasText: account.displayName })
        .filter({
          hasText: account.loginName.replace(/^(\d{3})\d+(\d{4})$/, '$1****$2'),
        })
      assert(await row.count())
      assert(
        !(await admin.locator('.el-table').innerText()).includes(
          account.loginName,
        ),
      )
    }
  }
  const requests = []
  const onRequest = (request) => {
    if (
      request.method() === 'PATCH' &&
      request.url().includes('/admin/accounts/')
    )
      requests.push(request.url())
  }
  admin.on('request', onRequest)
  try {
    const first = admin
      .getByRole('button', { name: '停用', exact: true })
      .first()
    await first.click()
    const confirmation = admin.getByRole('dialog', {
      name: '停用这个买家账号？',
      exact: true,
    })
    await confirmation.waitFor()
    await confirmation
      .getByRole('button', { name: '确认停用', exact: true })
      .waitFor()
    await confirmation
      .getByText('已产生的订单与交易记录将保留。', { exact: false })
      .waitFor()
    await shot('account-confirmation')
    await confirmation
      .getByRole('button', { name: '取消', exact: true })
      .click()
    await confirmation.waitFor({ state: 'hidden' })
    assert.equal(requests.length, 0)
  } finally {
    admin.off('request', onRequest)
  }
  await admin.goto(url('products'))
  await ready()
  fs.writeFileSync(
    path.join(out, 'result.json'),
    JSON.stringify(
      {
        reference: 'DAHZCL8Qxkk',
        dimensions,
        stockRoundTrip: id,
        categoryRoundTrip: leaf.id,
        cancelledAccountMutations: requests.length,
      },
      null,
      2,
    ),
  )
  mark(
    'Buyer-disable confirmation explains session impact and cancelling it makes no account mutation',
  )
}
module.exports = { checkAdminDesign }
