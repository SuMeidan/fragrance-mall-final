const assert = require('node:assert/strict')
const path = require('node:path')
const fs = require('node:fs')

// Controlled browser streams exercise the compiled UI, without paid cloud calls.
async function checkAdvisorStreaming(page, productId, output, mark) {
  await page.addInitScript(
    ({ productId }) => {
      const original = window.fetch.bind(window)
      const state = { requests: [], aborted: 0, release: null, fail: null }
      window.__scentStreamFixture = state
      window.fetch = (resource, init) => {
        if (
          typeof resource !== 'string' ||
          !resource.endsWith('/api/v1/advisor/chat/stream')
        )
          return original(resource, init)
        state.requests.push(JSON.parse(init.body))
        let closed = false
        const encoder = new TextEncoder()
        return Promise.resolve(
          new Response(
            new ReadableStream({
              start(controller) {
                const send = (event, message) => {
                  const bytes = encoder.encode(
                    `event: ${event}\r\ndata: ${JSON.stringify({ message })}\r\n\r\n`,
                  )
                  for (let offset = 0; offset < bytes.length; offset += 7)
                    controller.enqueue(bytes.slice(offset, offset + 7))
                }
                const partial =
                  '**已开始推荐🌿**\n\n[查看商品](/products/' + productId + ')'
                send('status', '正在查询商品')
                send('text', partial)
                state.release = () => {
                  if (closed) return
                  const full =
                    partial + '\n\n- 完整的推荐说明\n- 不喜欢甜香，预算50元'
                  send('text', full)
                  send('done', full)
                  closed = true
                  controller.close()
                }
                state.fail = () => {
                  if (closed) return
                  closed = true
                  controller.error(
                    new TypeError('controlled interrupted stream'),
                  )
                }
                init.signal.addEventListener(
                  'abort',
                  () => {
                    if (closed) return
                    closed = true
                    state.aborted++
                    controller.error(new DOMException('cleared', 'AbortError'))
                  },
                  { once: true },
                )
              },
            }),
            { headers: { 'Content-Type': 'text/event-stream;charset=UTF-8' } },
          ),
        )
      }
    },
    { productId },
  )
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.goto(new URL('/', page.url()).href)
  const launcher = page.getByRole('button', { name: '香氛顾问', exact: true })
  assert.equal(await launcher.getAttribute('aria-expanded'), 'false')
  await page.screenshot({
    path: path.join(output, 'advisor-welcome-launcher.png'),
  })
  await page.getByRole('button', { name: '香氛顾问', exact: true }).click()
  await page.waitForFunction(
    () => !document.querySelector('#advisor-input').disabled,
  )
  assert.equal(await page.locator('.advisor-suggestions button').count(), 3)
  assert.equal(
    await page.locator('.advisor-welcome-top h2').innerText(),
    '想选一款\n什么样的香气？',
  )
  assert(
    await page.getByRole('button', { name: '开始新的香氛咨询' }).isDisabled(),
  )
  assert.equal(
    await page.evaluate(() => window.__scentStreamFixture.requests.length),
    0,
  )
  const layouts = []
  const fits = async () => {
    // A resize acknowledgement precedes the next responsive layout frame.
    // Wait for the panel's actual viewport contract, then measure all children.
    await page.waitForFunction(() => {
      const r = document.querySelector('.advisor-panel').getBoundingClientRect()
      return (
        r.x >= 0 &&
        r.y >= 0 &&
        r.right <= innerWidth + 1 &&
        r.bottom <= innerHeight + 1
      )
    })
    const layout = await page.evaluate(() => {
      const bounds = (selector) => {
        const r = document.querySelector(selector).getBoundingClientRect()
        return {
          x: r.x,
          y: r.y,
          right: r.right,
          bottom: r.bottom,
          width: r.width,
          height: r.height,
        }
      }
      return {
        panel: bounds('.advisor-panel'),
        field: bounds('#advisor-input'),
        composer: bounds('.advisor-composer'),
        log: bounds('.advisor-log'),
        welcome: bounds('.advisor-welcome-content'),
        width: innerWidth,
        height: innerHeight,
        scrollWidth: document.documentElement.scrollWidth,
      }
    })
    layouts.push(layout)
    fs.writeFileSync(
      path.join(output, 'advisor-welcome-layouts.json'),
      JSON.stringify(layouts, null, 2),
    )
    assert(layout.panel.x >= 0 && layout.panel.y >= 0, JSON.stringify(layout))
    assert(
      layout.panel.right <= layout.width + 1 &&
        layout.panel.bottom <= layout.height + 1,
    )
    assert(layout.composer.bottom <= layout.panel.bottom + 1)
    assert(layout.field.height >= 30 && layout.log.height > 0)
    assert(layout.log.bottom <= layout.composer.y + 1)
    assert(layout.scrollWidth <= layout.width + 1)
    if (layout.height >= 844)
      assert(
        layout.welcome.bottom <= layout.log.bottom + 1,
        JSON.stringify(layout),
      )
  }
  await fits()
  await page.screenshot({
    path: path.join(output, 'advisor-welcome-desktop.png'),
  })
  await page.locator('#advisor-input').fill('未发送的偏好')
  await page.keyboard.press('Escape')
  assert.equal(await page.locator('.advisor-panel').count(), 0)
  assert(await launcher.evaluate((node) => node === document.activeElement))
  await launcher.click()
  assert.equal(
    await page.locator('#advisor-input').inputValue(),
    '未发送的偏好',
  )
  await page.locator('#advisor-input').fill('')
  mark(
    'Advisor welcome card has three topics, no automatic question, preserved draft and keyboard focus',
  )

  await page.setViewportSize({ width: 390, height: 844 })
  await fits()
  await page.screenshot({
    path: path.join(output, 'advisor-welcome-mobile.png'),
  })
  await page.setViewportSize({ width: 390, height: 480 })
  await fits()
  await page.screenshot({
    path: path.join(output, 'advisor-welcome-short-viewport.png'),
  })
  await page.emulateMedia({ reducedMotion: 'reduce' })
  assert.equal(
    await page
      .locator('.advisor-panel')
      .evaluate((node) => getComputedStyle(node).animationName),
    'none',
  )
  await page.emulateMedia({ reducedMotion: 'no-preference' })
  await page.setViewportSize({ width: 1440, height: 1000 })
  mark(
    'Advisor keeps composer inside desktop, mobile and short viewports and respects reduced motion',
  )

  await page.locator('#advisor-input').fill('中文输入中')
  await page
    .locator('#advisor-input')
    .dispatchEvent('keydown', { key: 'Enter', isComposing: true })
  await page.locator('#advisor-input').press('Shift+Enter')
  assert.equal(
    await page.evaluate(() => window.__scentStreamFixture.requests.length),
    0,
  )
  assert((await page.locator('#advisor-input').inputValue()).includes('\n'))
  await page.locator('#advisor-input').fill('通过键盘提问')
  await page.locator('#advisor-input').press('Enter')
  await page.waitForFunction(
    () => window.__scentStreamFixture.requests.length === 1,
  )
  await page.evaluate(() => window.__scentStreamFixture.release())
  await page.waitForFunction(
    () =>
      document.querySelector('.advisor-log').getAttribute('aria-busy') ===
      'false',
  )
  await page.getByRole('button', { name: '开始新的香氛咨询' }).click()
  assert.equal(await page.locator('.advisor-message').count(), 0)
  await page
    .getByRole('button', { name: '清爽一点，不要甜香', exact: false })
    .click()
  await page.waitForFunction(
    () => window.__scentStreamFixture.requests.length === 2,
  )
  assert.equal(
    await page.evaluate(
      () => window.__scentStreamFixture.requests.at(-1).message,
    ),
    '清爽一点，不要甜香',
  )
  await page.getByRole('button', { name: '关闭香氛顾问' }).click()
  await page.evaluate(() => window.__scentStreamFixture.release())
  assert.equal(await launcher.getAttribute('aria-expanded'), 'false')
  await launcher.click()
  await page.waitForFunction(
    () =>
      document.querySelector('.advisor-log').getAttribute('aria-busy') ===
      'false',
  )
  assert.equal(await page.locator('.advisor-message.assistant').count(), 1)
  await page.getByRole('button', { name: '开始新的香氛咨询' }).click()
  // Reset fixture counters only, after both completed conversations were cleared.
  await page.evaluate(() => {
    window.__scentStreamFixture.requests = []
  })
  mark(
    'Advisor Enter and IME rules, real quick-topic request and close-during-stream preserve one reply',
  )
  const send = async (text) => {
    await page.locator('#advisor-input').fill(text)
    await page.getByRole('button', { name: '发送香氛咨询' }).click()
    await page
      .locator('.advisor-message.incomplete strong')
      .getByText('已开始推荐🌿', { exact: true })
      .waitFor()
  }
  await send('50元以内，不喜欢甜香')
  assert.equal(await page.locator('.advisor-markdown a').count(), 0)
  assert.equal(
    await page.locator('.advisor-log').getAttribute('aria-busy'),
    'true',
  )
  assert(await page.getByRole('button', { name: '发送香氛咨询' }).isDisabled())
  assert.equal(
    await page.getByText('完整的推荐说明', { exact: true }).count(),
    0,
  )
  await page.screenshot({
    path: path.join(output, 'advisor-streaming-partial.png'),
  })
  await page.evaluate(() => window.__scentStreamFixture.release())
  await page.waitForFunction(
    () =>
      document.querySelector('.advisor-log').getAttribute('aria-busy') ===
      'false',
  )
  assert.equal(await page.locator('.advisor-message.incomplete').count(), 0)
  assert.equal(await page.locator('.advisor-markdown a').count(), 1)
  assert.equal(
    await page.locator('.advisor-markdown strong').innerText(),
    '已开始推荐🌿',
  )
  mark(
    'Advisor shows real incremental Markdown before completion and only then enables product links',
  )

  await page.getByRole('button', { name: '清空', exact: true }).click()
  await send('验证中途断线')
  await page.evaluate(() => window.__scentStreamFixture.fail())
  await page
    .getByRole('button', { name: '继续等待结果', exact: true })
    .waitFor()
  assert.equal(await page.locator('.advisor-message.incomplete').count(), 1)
  await page.getByRole('button', { name: '继续等待结果', exact: true }).click()
  await page.waitForFunction(
    () => window.__scentStreamFixture.requests.length === 3,
  )
  const requests = await page.evaluate(
    () => window.__scentStreamFixture.requests,
  )
  assert.deepEqual(requests[2], requests[1])
  await page.evaluate(() => window.__scentStreamFixture.release())
  await page.waitForFunction(
    () =>
      document.querySelector('.advisor-log').getAttribute('aria-busy') ===
      'false',
  )
  assert.equal(await page.locator('.advisor-message.user').count(), 1)
  assert.equal(await page.locator('.advisor-message.assistant').count(), 1)
  assert.equal(await page.getByRole('alert').count(), 0)
  mark(
    'Advisor retains interrupted text and retries the same request without duplicating messages',
  )

  await page.getByRole('button', { name: '清空', exact: true }).click()
  await send('即将清空的旧问题')
  const previous = await page.evaluate(
    () => window.__scentStreamFixture.requests.at(-1).conversationId,
  )
  await page.getByRole('button', { name: '清空', exact: true }).click()
  await page.evaluate(() => window.__scentStreamFixture.release())
  await page.waitForFunction(() => window.__scentStreamFixture.aborted === 1)
  assert.equal(await page.locator('.advisor-message').count(), 0)
  await send('清空后的新咨询')
  const current = await page.evaluate(() =>
    window.__scentStreamFixture.requests.at(-1),
  )
  assert.notEqual(current.conversationId, previous)
  assert.equal(current.turn, 1)
  await page.evaluate(() => window.__scentStreamFixture.release())
  await page.waitForFunction(
    () =>
      document.querySelector('.advisor-log').getAttribute('aria-busy') ===
      'false',
  )
  await page.setViewportSize({ width: 390, height: 844 })
  assert.equal(
    await page.getByText('即将清空的旧问题', { exact: true }).count(),
    0,
  )
  assert(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth + 1,
    ),
  )
  await page.screenshot({
    path: path.join(output, 'advisor-streaming-mobile.png'),
  })
  await page.getByRole('button', { name: '清空', exact: true }).click()
  mark(
    'Advisor clear aborts an active stream, isolates the next conversation and keeps mobile layout',
  )
}
module.exports = { checkAdvisorStreaming }
