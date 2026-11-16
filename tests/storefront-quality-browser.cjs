// Regression checks against the isolated CI database. No real model calls.
const assert = require('node:assert/strict')
const path = require('node:path')

async function checkStorefrontQuality(page, origin, output, mark, product) {
  const go = (route) => page.goto(new URL(route, origin).href)
  await page.waitForFunction(
    () => document.querySelectorAll('.depth-plate').length === 5,
  )
  assert.equal(await page.locator('#voices,.home-showcase-reviews').count(), 0)
  await page.goto(new URL('/catalog?categoryId=4&familyId=1', origin).href)
  await page.locator('.product-card').first().waitFor()
  assert(
    await page
      .getByRole('combobox', { name: '香型', exact: true })
      .isDisabled(),
  )
  assert(!new URL(page.url()).searchParams.has('familyId'))
  await page.getByRole('combobox', { name: '商品类型', exact: true }).click()
  await page.getByRole('option', { name: '香薰机', exact: true }).click()
  await page.locator('.product-card').first().waitFor()
  assert(
    (await page.locator('.product-card .eyebrow').allTextContents()).every(
      (text) => text.includes('香薰机'),
    ),
  )
  await page.goto(new URL('/catalog?categoryId=504', origin).href)
  await page.locator('.product-card').first().waitFor()
  assert.equal(await page.title(), '商品目录 · SCENT')
  assert(
    (await page.locator('.product-card .eyebrow').allTextContents()).every(
      (text) => text.includes('成品香氛礼盒'),
    ),
  )
  mark(
    'Actual category/type navigation works; tools clear stale scent before querying and keep the scent filter disabled',
  )

  const searchBox = page.getByRole('textbox', { name: '搜索商品', exact: true })
  const searchBefore = await searchBox.boundingBox()
  await page.getByRole('button', { name: '打开商品搜索', exact: true }).click()
  const searchAfter = await searchBox.boundingBox()
  assert.equal(
    await searchBox.evaluate((e) => e === document.activeElement),
    true,
  )
  for (const k of ['x', 'y', 'width', 'height'])
    assert(Math.abs(searchBefore[k] - searchAfter[k]) < 1)
  assert(searchAfter.x > page.viewportSize().width / 2)
  assert.equal(await page.locator('.product-card .image-toggle').count(), 0)
  const brand = await page.locator('.site-header .wordmark').boundingBox()
  assert(
    Math.abs(brand.x + brand.width / 2 - page.viewportSize().width / 2) < 1,
  )
  mark(
    'Canvas catalog layout has a centered brand and stable title-side search; focusing never shifts the page and product images have no corner badge',
  )

  await page
    .getByRole('textbox', { name: '搜索商品', exact: true })
    .fill('质量回归不存在的作品')
  await page
    .getByRole('textbox', { name: '搜索商品', exact: true })
    .press('Enter')
  await page.getByText(/未找到与“质量回归不存在的作品”相关/).waitFor()
  await page
    .getByRole('button', { name: '清空条件，查看全部作品', exact: true })
    .click()
  await page.waitForURL(new URL('/catalog', origin).href)
  await page.locator('.product-card').first().waitFor()
  assert.equal(
    await page.getByRole('textbox', { name: '搜索商品' }).inputValue(),
    '',
  )
  mark(
    'Real keyword search has an accurate empty state and restores the catalog in one action',
  )

  await go('/products/' + product.id)
  await page.getByRole('heading', { name: product.name, exact: true }).waitFor()
  await page.waitForFunction(
    (name) => document.title === name + ' · SCENT',
    product.name,
  )
  assert.match(
    await page.locator('.description').innerText(),
    /仅用于本地工程验证/,
  )
  assert.doesNotMatch(
    await page.locator('.description').innerText(),
    /明确排斥该香调者不选|虚构验证品，结束后下架/,
  )
  assert.equal(await page.locator('.subtitle').innerText(), product.subtitle)
  assert.match(
    await page.locator('.detail-disclosure').innerText(),
    /SCENT虚构教学商品/,
  )
  await page.getByRole('button', { name: '加入购物车', exact: true }).click()
  await page.waitForURL('**/login?**')
  assert.equal(await page.title(), '登录 · SCENT')
  for (const viewport of [
    { width: 320, height: 568 },
    { width: 390, height: 480 },
    { width: 390, height: 844 },
  ]) {
    await page.setViewportSize(viewport)
    await page.waitForFunction(
      () =>
        getComputedStyle(document.querySelector('.advisor-trigger')).display ===
        'none',
    )
    const submit = page.locator('.auth-form .btn')
    await submit.scrollIntoViewIfNeeded()
    assert(
      await submit.evaluate((button) => {
        const r = button.getBoundingClientRect()
        const hit = document.elementFromPoint(
          r.x + r.width / 2,
          r.y + r.height / 2,
        )
        return hit === button || button.contains(hit)
      }),
      'Login action must receive taps without the advisor covering it',
    )
    assert(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth + 1,
      ),
    )
    await page.screenshot({
      path: path.join(
        output,
        `quality-login-${viewport.width}-${viewport.height}.png`,
      ),
    })
  }
  mark(
    'Login at 320px and short mobile sizes has no advisor overlap and preserves the product return path',
  )

  const caps = '**/api/v1/capabilities'
  await page.route(caps, (route) =>
    route.fulfill({
      json: {
        resultCode: 200,
        data: { advisorConfigured: true, paymentConfigured: false },
      },
    }),
  )
  await go('/')
  await page.getByRole('button', { name: '香氛顾问', exact: true }).click()
  await page.waitForFunction(
    () => !document.querySelector('#advisor-input')?.disabled,
  )
  const field = page.locator('#advisor-input')
  await field.fill('保留草稿，不发送')
  const styles = await page.evaluate(() => {
    function background(element) {
      for (let p = element; p; p = p.parentElement) {
        const color = getComputedStyle(p).backgroundColor
        if (color.startsWith('rgb(')) return color
      }
      return 'rgb(255, 255, 255)'
    }
    return [
      '.advisor-header strong',
      '.advisor-identity > div > span',
      '.advisor-header button:last-child',
      '.advisor-suggestions strong',
      '.advisor-footer',
      '#advisor-input',
    ].map((selector) => {
      const e = document.querySelector(selector),
        s = getComputedStyle(e)
      return {
        selector,
        font: parseFloat(s.fontSize),
        color: s.color,
        background: background(e),
        placeholder: getComputedStyle(e, '::placeholder').color,
      }
    })
  })
  function contrast(a, b) {
    const light = (color) =>
      color
        .match(/[\d.]+/g)
        .slice(0, 3)
        .map(Number)
        .map((v) => v / 255)
        .map((v) => (v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4))
        .reduce((sum, v, i) => sum + v * [0.2126, 0.7152, 0.0722][i], 0)
    const values = [light(a), light(b)].sort((x, y) => y - x)
    return (values[0] + 0.05) / (values[1] + 0.05)
  }
  for (const style of styles) {
    assert(style.font >= 12, style.selector + ' needs readable supporting text')
    assert(
      contrast(style.color, style.background) >= 4.5,
      style.selector + ' needs 4.5:1 contrast',
    )
  }
  assert(styles.at(-1).font >= 16)
  assert(contrast(styles.at(-1).placeholder, 'rgb(255, 255, 255)') >= 4.5)
  for (const viewport of [
    { width: 320, height: 568 },
    { width: 390, height: 480 },
    { width: 390, height: 844 },
    { width: 768, height: 1024 },
    { width: 1440, height: 1000 },
  ]) {
    await page.setViewportSize(viewport)
    await page.waitForFunction(() => {
      const panel = document
        .querySelector('.advisor-panel')
        .getBoundingClientRect()
      const input = document
        .querySelector('#advisor-input')
        .getBoundingClientRect()
      return (
        panel.x >= 0 &&
        panel.y >= 0 &&
        panel.right <= innerWidth + 1 &&
        panel.bottom <= innerHeight + 1 &&
        input.y >= panel.y &&
        input.bottom <= panel.bottom
      )
    })
    assert(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth + 1,
      ),
    )
    await page.screenshot({
      path: path.join(
        output,
        `quality-advisor-${viewport.width}-${viewport.height}.png`,
      ),
    })
  }
  await field.press('Escape')
  assert.equal(await page.locator('.advisor-panel').count(), 0)
  assert.equal(
    await page.evaluate(() =>
      document.activeElement.getAttribute('aria-label'),
    ),
    '香氛顾问',
  )
  await page.getByRole('button', { name: '香氛顾问', exact: true }).click()
  assert.equal(await field.inputValue(), '保留草稿，不发送')
  await field.fill('')
  await field.press('Escape')
  await page.unroute(caps)
  mark(
    'Advisor welcome keeps draft and keyboard focus, readable contrast and input at five viewport sizes',
  )
  await page.setViewportSize({ width: 1440, height: 1000 })
  await go('/')
}
module.exports = { checkStorefrontQuality }
