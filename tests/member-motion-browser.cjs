// Approved exhibition motion against the real public API, plus retained product-image gestures.
const assert = require('node:assert/strict')
const path = require('node:path')
async function checkDepthHandoff(page, origin, output, mark) {
  const original = page.viewportSize(),
    records = []
  try {
    await page.evaluate(() => document.fonts.ready)
    for (const viewport of [
      { width: 1440, height: 1000 },
      { width: 390, height: 844 },
      { width: 320, height: 568 },
      { width: 390, height: 480 },
    ]) {
      await page.setViewportSize(viewport)
      for (let chapter = 0; chapter < 4; chapter++)
        for (const fraction of [0, 0.3, 0.4, 0.55, 0.78, 0.92, 1]) {
          await page.evaluate((step) => {
            const h = document.querySelector('.depth-stage').offsetHeight,
              span = document.querySelector('.depth-story').offsetHeight - h
            scrollTo({
              top: h * 0.9 + (step / 4) * (span - h * 0.9),
              behavior: 'instant',
            })
          }, chapter + fraction)
          await page.waitForTimeout(450)
          const state = await page.evaluate(() => {
            const cards = [...document.querySelectorAll('.depth-plate')]
            const readable = cards.filter((e) => {
              const c = e.querySelector('.plate-copy'),
                r = c.getBoundingClientRect()
              return (
                Number(getComputedStyle(c).opacity) > 0.05 &&
                Number(getComputedStyle(e).opacity) > 0.1 &&
                r.bottom > 0 &&
                r.top < innerHeight
              )
            })
            const actions = cards
              .filter((e) => !e.inert)
              .flatMap((e) =>
                [...e.querySelectorAll('.plate-copy a')].map((a) => {
                  const r = a.getBoundingClientRect(),
                    hit = document.elementFromPoint(
                      r.x + r.width / 2,
                      r.y + r.height / 2,
                    )
                  return {
                    label: a.innerText,
                    visible: r.top >= 0 && r.bottom <= innerHeight,
                    receivesPointer: hit === a || a.contains(hit),
                  }
                }),
              )
            return { readable: readable.length, actions }
          })
          records.push({ viewport, chapter, fraction, ...state })
          assert(state.readable <= 1, JSON.stringify(records.at(-1)))
          assert(
            state.actions.every((a) => a.visible && a.receivesPointer),
            JSON.stringify(records.at(-1)),
          )
        }
      await page.screenshot({
        path: path.join(
          output,
          `depth-handoff-${viewport.width}-${viewport.height}.png`,
        ),
      })
    }
    require('node:fs').writeFileSync(
      path.join(output, 'depth-handoff.json'),
      JSON.stringify(records, null, 2),
    )
    const action = page
        .locator('.depth-plate:not([inert]) .plate-copy a')
        .first(),
      target = await action.getAttribute('href')
    await action.click()
    await page.waitForURL(new URL(target, origin).href)
    await page.goBack()
    await page.waitForFunction(
      () => document.querySelectorAll('.depth-plate').length === 5,
    )
    mark(
      'All 112 scroll handoff states keep one readable card and unblocked actions; compact controls and a real product click work',
    )
  } finally {
    await page.setViewportSize(original)
  }
}
async function checkMemberMotion(page, origin, output, mark) {
  const viewport = page.viewportSize(),
    wasReduced = await page.evaluate(
      () => matchMedia('(prefers-reduced-motion: reduce)').matches,
    )
  try {
    await page.emulateMedia({ reducedMotion: 'no-preference' })
    await page.goto(origin.href, { waitUntil: 'domcontentloaded' })
    await page.locator('.center-opening').waitFor()
    assert.equal(await page.locator('.curtain-word').count(), 2)
    await page.getByRole('button', { name: '跳过开场', exact: true }).click()
    await page.locator('.center-opening').waitFor({ state: 'detached' })
    assert.equal(
      await page.getByRole('button', { name: /播放|暂停|重播/ }).count(),
      0,
    )
    mark(
      'Central SCENT opening skips cleanly; no playback controls are rendered',
    )
    await page.waitForFunction(
      () => document.querySelectorAll('.depth-plate').length === 5,
    )
    assert.deepEqual(
      await page
        .locator('.depth-plate')
        .evaluateAll((es) => es.map((e) => +e.dataset.category)),
      [1, 2, 3, 4, 5],
    )
    const words = page.getByRole('group', {
      name: 'SCENT立体字标，可拖动或使用方向键',
      exact: true,
    })
    await words.focus()
    await words.press('ArrowRight')
    await page.waitForTimeout(500)
    const pose = await page
      .locator('.experience-brand')
      .evaluate((e) => getComputedStyle(e).transform)
    assert.notEqual(pose, 'none')
    await words.press('Home')
    await page.mouse.move(2, 2)
    await page.mouse.wheel(0, 520)
    await page.waitForTimeout(650)
    const join = await page.locator('.depth-plate').first().boundingBox()
    assert(join.height > page.viewportSize().height * 0.7)
    await page.screenshot({ path: path.join(output, 'refined-join.png') })
    await page.mouse.wheel(0, 1600)
    await page.waitForTimeout(650)
    assert(
      (await page
        .locator('.depth-plate')
        .evaluateAll(
          (es) => es.filter((e) => +e.style.opacity > 0.5).length,
        )) >= 2,
    )
    await page.screenshot({ path: path.join(output, 'refined-depth.png') })
    mark(
      'Real five-category selection, damped type, continuous first-image contraction and overlapping perspective respond to native scroll',
    )
    await checkDepthHandoff(page, origin, output, mark)
    await page.locator('.experience-end').scrollIntoViewIfNeeded()
    await page.mouse.move(-10, -10)
    assert.equal(await page.locator('.floating-lane').count(), 3)
    assert.equal(
      await page
        .locator('.floating-rows')
        .evaluate((e) => getComputedStyle(e).transform),
      'none',
    )
    let total = 0
    const first = await page.request.get(
      new URL('/api/v1/catalog/products?pageSize=40', origin).href,
    )
    total = (await first.json()).data.total
    assert.equal(
      await page.locator('.floating-group:not([aria-hidden]) a').count(),
      total,
    )
    assert.equal(
      await page
        .locator('.floating-group[aria-hidden] a:not([tabindex="-1"])')
        .count(),
      0,
    )
    const original = page
      .locator('.floating-group:not([aria-hidden]) a')
      .first()
    await original.focus()
    assert.equal(
      await original.evaluate(
        (e) => getComputedStyle(e.closest('.floating-track')).animationName,
      ),
      'none',
    )
    const target = await original.getAttribute('href')
    await original.click()
    await page.waitForURL(new URL(target, origin).href)
    mark(
      'All live products flow horizontally in three rows; keyboard freezes and reveals a real product link without duplicate tab stops',
    )
    await page.emulateMedia({ reducedMotion: 'reduce' })
    await page.goto(origin.href)
    await page.waitForFunction(
      () => document.querySelectorAll('.depth-plate').length === 5,
    )
    assert.equal(await page.locator('.center-opening').count(), 0)
    assert(
      await page
        .locator('.depth-plate')
        .evaluateAll((es) =>
          es.every((e) => !e.inert && getComputedStyle(e).transform === 'none'),
        ),
    )
    assert.equal(
      await page
        .locator('.floating-track')
        .first()
        .evaluate((e) => getComputedStyle(e).animationName),
      'none',
    )
    assert.equal(
      await page.locator('#voices,.home-showcase-reviews').count(),
      0,
    )
    mark(
      'Reduced motion exposes five linear category cards and static product rows; homepage reviews stay absent',
    )
    for (const size of [
      { width: 320, height: 568 },
      { width: 390, height: 480 },
      { width: 390, height: 844 },
      { width: 768, height: 1024 },
      { width: 1440, height: 1000 },
    ]) {
      await page.setViewportSize(size)
      assert(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth + 1,
        ),
      )
      assert.equal(
        await page
          .locator('.site-header')
          .getByRole('link', { name: '购物袋', exact: true })
          .count(),
        1,
      )
    }
    await page.screenshot({
      path: path.join(output, 'refined-home-desktop.png'),
    })
    await page.setViewportSize({ width: 390, height: 844 })
    await page.screenshot({
      path: path.join(output, 'refined-home-mobile.png'),
    })
    mark(
      'Exhibition and single shopping-bag navigation fit five desktop/phone viewport sizes',
    )
    const phoneContext = await page
      .context()
      .browser()
      .newContext({
        viewport: { width: 390, height: 844 },
        isMobile: true,
        hasTouch: true,
        reducedMotion: 'no-preference',
      })
    try {
      const phone = await phoneContext.newPage()
      await phone.goto(origin.href, { waitUntil: 'domcontentloaded' })
      await phone.getByRole('button', { name: '跳过开场', exact: true }).click()
      const touch = await phoneContext.newCDPSession(phone)
      await touch.send('Input.dispatchTouchEvent', {
        type: 'touchStart',
        touchPoints: [{ x: 190, y: 700 }],
      })
      for (let i = 1; i <= 5; i++) {
        await touch.send('Input.dispatchTouchEvent', {
          type: 'touchMove',
          touchPoints: [{ x: 190, y: 700 - i * 100 }],
        })
        await phone.waitForTimeout(40)
      }
      await touch.send('Input.dispatchTouchEvent', {
        type: 'touchEnd',
        touchPoints: [],
      })
      await phone.waitForTimeout(400)
      assert(await phone.evaluate(() => scrollY > 200))
      assert(
        await phone.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth + 1,
        ),
      )
    } finally {
      await phoneContext.close()
    }
    mark(
      'Actual mobile upward touch scroll remains native with no pointer-capture trap',
    )
    await checkMobileHomeMotion(page, origin, output, mark)
  } finally {
    await page.emulateMedia({
      reducedMotion: wasReduced ? 'reduce' : 'no-preference',
    })
    await page.setViewportSize(viewport)
    await page.goto(origin.href)
  }
}
async function checkMemberImageGestures(page, origin, output, mark, product) {
  const context = await page
    .context()
    .browser()
    .newContext({
      viewport: { width: 390, height: 844 },
      isMobile: true,
      hasTouch: true,
      reducedMotion: 'reduce',
    })
  const phone = await context.newPage(),
    errors = []
  phone.on('pageerror', (error) => errors.push(String(error)))
  async function swipe(locator) {
    await locator.scrollIntoViewIfNeeded()
    const box = await locator.boundingBox()
    assert(box)
    const touch = await context.newCDPSession(phone)
    try {
      const point = {
        x: box.x + box.width * 0.8,
        y: box.y + box.height * 0.5,
        id: 1,
      }
      await touch.send('Input.dispatchTouchEvent', {
        type: 'touchStart',
        touchPoints: [point],
      })
      for (let step = 1; step <= 6; step++) {
        await touch.send('Input.dispatchTouchEvent', {
          type: 'touchMove',
          touchPoints: [
            { ...point, x: point.x - (box.width * 0.55 * step) / 6 },
          ],
        })
        await phone.waitForTimeout(35)
      }
      await touch.send('Input.dispatchTouchEvent', {
        type: 'touchEnd',
        touchPoints: [],
      })
      await phone.waitForTimeout(100)
    } finally {
      await touch.detach()
    }
  }
  try {
    const catalog = new URL('/catalog', origin)
    catalog.searchParams.set('keyword', product.name)
    await phone.goto(catalog.href)
    const card = phone.locator('.product-card').first()
    await card.locator('.product-image img').first().waitFor()
    await swipe(card.locator('.product-image'))
    await phone.waitForFunction(
      () =>
        Number(
          getComputedStyle(document.querySelector('.product-card .scent-image'))
            .opacity,
        ) > 0.99,
    )
    assert.equal(await card.locator('.image-toggle').count(), 0)
    assert.equal(new URL(phone.url()).pathname, '/catalog')
    await card.locator('.product-card-link').focus()
    await card.locator('.product-card-link').press('ArrowLeft')
    await phone.waitForFunction(
      () =>
        Number(
          getComputedStyle(document.querySelector('.product-card .scent-image'))
            .opacity,
        ) < 0.01,
    )
    await phone.goto(new URL('/products/' + product.id, origin).href)
    await phone
      .getByRole('heading', { name: product.name, exact: true })
      .waitFor()
    await swipe(phone.locator('.detail-image'))
    assert.equal(
      await phone
        .getByRole('button', { name: '香调意象', exact: true })
        .getAttribute('aria-pressed'),
      'true',
    )
    await phone
      .getByRole('region', { name: '商品图片浏览', exact: true })
      .focus()
    await phone
      .getByRole('region', { name: '商品图片浏览', exact: true })
      .press('ArrowRight')
    assert.equal(
      await phone
        .getByRole('button', { name: '场景图', exact: true })
        .getAttribute('aria-pressed'),
      'true',
    )
    assert.deepEqual(errors, [])
    await phone.screenshot({
      path: path.join(output, 'member-detail-touch.png'),
    })
    mark(
      'Real catalog and three-image detail support touch and arrow keys without accidental navigation',
    )
  } finally {
    await context.close()
  }
}
async function checkMobileHomeMotion(page, origin, output, mark) {
  const context = await page
    .context()
    .browser()
    .newContext({
      viewport: { width: 375, height: 667 },
      isMobile: true,
      hasTouch: true,
      locale: 'zh-CN',
      reducedMotion: 'no-preference',
    })
  await context.addInitScript(() => {
    // Observe application capture calls, separate from implicit browser touch capture.
    window.__scentMotionInput = { captures: [], events: [] }
    for (const type of [
      'pointerdown',
      'pointermove',
      'pointerup',
      'pointercancel',
      'gotpointercapture',
      'lostpointercapture',
    ])
      document.addEventListener(
        type,
        (event) => {
          const root = document.querySelector('.exhibition-home'),
            hero = document.querySelector('.film-hero')
          window.__scentMotionInput.events.push({
            type,
            time: performance.now(),
            pointerId: event.pointerId,
            pointerType: event.pointerType,
            target: String(event.target.className),
            x: event.clientX,
            y: event.clientY,
            scroll: scrollY,
            heroCapture: !!hero?.hasPointerCapture(event.pointerId),
            dragging: !!root?.hasAttribute('data-dragging'),
          })
        },
        true,
      )
    const capture = Element.prototype.setPointerCapture
    Element.prototype.setPointerCapture = function (pointerId) {
      window.__scentMotionInput.captures.push({
        pointerId,
        target: String(this.className),
      })
      return capture.call(this, pointerId)
    }
  })
  const phone = await context.newPage(),
    touch = await context.newCDPSession(phone),
    records = [],
    errors = []
  phone.on('pageerror', (error) => errors.push(error.message))
  phone.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text())
  })
  async function swipe(from, to, moved) {
    await touch.send('Input.dispatchTouchEvent', {
      type: 'touchStart',
      touchPoints: [{ ...from, id: 1 }],
    })
    try {
      for (let step = 1; step <= 8; step++) {
        await touch.send('Input.dispatchTouchEvent', {
          type: 'touchMove',
          touchPoints: [
            {
              x: from.x + ((to.x - from.x) * step) / 8,
              y: from.y + ((to.y - from.y) * step) / 8,
              id: 1,
            },
          ],
        })
        await phone.waitForTimeout(35)
        if (moved) await moved(step)
      }
      // Stop before release so momentum cannot jump over an entire chapter.
      await phone.waitForTimeout(80)
    } finally {
      await touch.send('Input.dispatchTouchEvent', {
        type: 'touchEnd',
        touchPoints: [],
      })
    }
  }
  async function pose() {
    return phone.locator('.exhibition-home').evaluate((element) => ({
      time: performance.now(),
      x: Number(element.style.getPropertyValue('--exp-x')) || 0,
      y: Number(element.style.getPropertyValue('--exp-y')) || 0,
      dragging: element.hasAttribute('data-dragging'),
      captures: window.__scentMotionInput.captures.length,
      scroll: scrollY,
    }))
  }
  async function geometry() {
    return phone.evaluate(() => {
      const rect = (node) => {
          const r = node.getBoundingClientRect()
          return {
            top: r.top,
            bottom: r.bottom,
            left: r.left,
            right: r.right,
            width: r.width,
            height: r.height,
          }
        },
        visible = (node) => {
          if (!node) return false
          const style = getComputedStyle(node),
            r = node.getBoundingClientRect()
          return (
            style.display !== 'none' &&
            style.visibility !== 'hidden' &&
            Number(style.opacity) > 0.05 &&
            r.width > 0 &&
            r.height > 0 &&
            r.bottom > 0 &&
            r.top < innerHeight
          )
        },
        intersects = (a, b) =>
          Math.min(a.right, b.right) - Math.max(a.left, b.left) > 1 &&
          Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top) > 1,
        hit = (node) => {
          const r = rect(node),
            target = document.elementFromPoint(
              r.left + r.width / 2,
              r.top + r.height / 2,
            )
          return {
            label: node.getAttribute('aria-label') || node.textContent.trim(),
            visible:
              r.top >= -1 &&
              r.bottom <= innerHeight + 1 &&
              r.left >= -1 &&
              r.right <= innerWidth + 1,
            receivesPointer: target === node || node.contains(target),
            rect: r,
          }
        },
        plates = [...document.querySelectorAll('.depth-plate')],
        owners = plates.filter((plate) => !plate.inert),
        readable = plates.filter(
          (plate) =>
            visible(plate) && visible(plate.querySelector('.plate-copy')),
        ),
        obstacles = [
          '.site-header',
          '.depth-controls',
          '.experience-entry',
          '.advisor-trigger',
        ]
          .map((selector) => ({
            selector,
            element: document.querySelector(selector),
          }))
          .filter(({ element }) => visible(element))
          .map(({ selector, element }) => ({ selector, rect: rect(element) })),
        overlap = owners.flatMap((plate) => {
          const copy = rect(plate.querySelector('.plate-copy'))
          return obstacles
            .filter((obstacle) => intersects(copy, obstacle.rect))
            .map((obstacle) => ({
              category: plate.dataset.category,
              obstacle: obstacle.selector,
              copy,
              obstacleRect: obstacle.rect,
            }))
        }),
        actions = owners.flatMap((plate) =>
          [...plate.querySelectorAll('.plate-copy a')].map(hit),
        ),
        controls = [
          ...document.querySelectorAll('.depth-controls button:enabled'),
        ]
          .filter(visible)
          .map(hit)
      return {
        viewport: { width: innerWidth, height: innerHeight },
        stage: rect(document.querySelector('.depth-stage')),
        story: rect(document.querySelector('.depth-story')),
        storyEnded: document
          .querySelector('.exhibition-home')
          .classList.contains('story-ended'),
        scroll: scrollY,
        chapter: Number.parseInt(
          document.querySelector('.depth-chapter p').textContent,
          10,
        ),
        readable: readable.length,
        owners: owners.map((plate) => plate.dataset.category),
        actions,
        controls,
        overlap,
        overflow: document.documentElement.scrollWidth > innerWidth + 1,
      }
    })
  }
  function assertGeometry(state) {
    assert(!state.overflow, JSON.stringify(state))
    assert(state.readable <= 1, JSON.stringify(state))
    assert(state.owners.length <= 1, JSON.stringify(state))
    assert.deepEqual(state.overlap, [], JSON.stringify(state))
    assert(
      state.actions.every((action) => action.visible && action.receivesPointer),
      JSON.stringify(state),
    )
    assert(
      state.controls.every(
        (control) => control.visible && control.receivesPointer,
      ),
      JSON.stringify(state),
    )
  }
  try {
    await phone.goto(origin.href, { waitUntil: 'domcontentloaded' })
    await phone.locator('.center-opening').waitFor({ state: 'visible' })
    await phone.screenshot({
      path: path.join(output, 'mobile-opening-before-touch.png'),
    })
    const start = await pose()
    await swipe({ x: 188, y: 480 }, { x: 188, y: 210 }, async (step) => {
      if (step === 1)
        await phone.locator('.center-opening').waitFor({
          state: 'detached',
          timeout: 700,
        })
    })
    await phone.waitForTimeout(250)
    const vertical = await pose()
    records.push({ check: 'opening-vertical-swipe', start, end: vertical })
    assert(vertical.scroll > start.scroll + 80, JSON.stringify(vertical))
    assert.equal(vertical.captures, start.captures, JSON.stringify(vertical))
    assert(Math.abs(vertical.x) < 0.02, JSON.stringify(vertical))
    assert(Math.abs(vertical.y) < 0.02, JSON.stringify(vertical))
    assert(!vertical.dragging, JSON.stringify(vertical))
    await phone.waitForFunction(
      () => document.querySelectorAll('.depth-plate').length === 5,
    )
    await phone.evaluate(() => document.fonts.ready)
    await phone.evaluate(() => scrollTo({ top: 0, behavior: 'instant' }))
    await phone.waitForFunction(() => {
      const hero = document.querySelector('.film-hero')
      return (
        scrollY === 0 &&
        Number(getComputedStyle(hero).opacity) > 0.99 &&
        getComputedStyle(hero).pointerEvents === 'auto'
      )
    })
    await phone.evaluate(
      () =>
        new Promise((resolve) =>
          requestAnimationFrame(() => requestAnimationFrame(resolve)),
        ),
    )
    let horizontalPose = 0
    const horizontalStates = []
    await swipe({ x: 75, y: 220 }, { x: 285, y: 220 }, async (step) => {
      const state = await pose()
      horizontalStates.push({ step, ...state })
      horizontalPose = Math.max(horizontalPose, Math.abs(state.x))
    })
    records.push({
      check: 'horizontal-touch-perspective',
      horizontalPose,
      states: horizontalStates,
    })
    assert(horizontalPose > 0.05, `Horizontal touch pose=${horizontalPose}`)
    assert(horizontalStates.at(-1).dragging, JSON.stringify(horizontalStates))
    await phone.waitForFunction(() => {
      const root = document.querySelector('.exhibition-home')
      return (
        Math.abs(Number(root.style.getPropertyValue('--exp-x'))) < 0.02 &&
        Math.abs(Number(root.style.getPropertyValue('--exp-y'))) < 0.02 &&
        !root.hasAttribute('data-dragging')
      )
    })

    const forward = new Set([1]),
      backward = new Set([5]),
      native = [],
      capturesAfterHorizontal = (await pose()).captures
    let verticalTilt = 0
    for (const direction of [1, -1]) {
      let complete = false
      for (let index = 0; index < 35; index++) {
        const previous = await phone.evaluate(() => scrollY),
          viewport = phone.viewportSize(),
          y1 = viewport.height * (direction === 1 ? 0.72 : 0.28),
          y2 = viewport.height * (direction === 1 ? 0.28 : 0.72)
        await swipe(
          { x: viewport.width / 2, y: y1 },
          { x: viewport.width / 2, y: y2 },
          async (step) => {
            if (step === 4) {
              const state = await pose()
              verticalTilt = Math.max(
                verticalTilt,
                Math.abs(state.x),
                Math.abs(state.y),
              )
            }
          },
        )
        await phone.waitForTimeout(220)
        const state = await geometry()
        native.push({ direction, index, ...state })
        ;(direction === 1 ? forward : backward).add(state.chapter)
        if (
          (direction === 1 &&
            state.chapter === 5 &&
            state.owners.length === 1) ||
          (direction === -1 && state.scroll <= 3)
        ) {
          complete = true
          break
        }
        assert(
          direction * (state.scroll - previous) > 20,
          JSON.stringify(native.at(-1)),
        )
      }
      assert(complete, `Native touch traversal did not finish: ${direction}`)
    }
    records.push({ check: 'native-five-chapter-roundtrip', native })
    assert.deepEqual([...forward].sort(), [1, 2, 3, 4, 5])
    assert.deepEqual([...backward].sort(), [1, 2, 3, 4, 5])
    assert(
      verticalTilt < 0.02,
      `Vertical touch tilted the scene: ${verticalTilt}`,
    )
    assert.equal((await pose()).captures, capturesAfterHorizontal)

    for (const viewport of [
      { width: 375, height: 667 },
      { width: 390, height: 844 },
      { width: 320, height: 568 },
      { width: 390, height: 480 },
      { width: 844, height: 390 },
    ]) {
      await phone.setViewportSize(viewport)
      for (const step of [
        ...[0, 1, 2, 3].flatMap((chapter) =>
          [0, 0.4, 0.82, 0.95].map((fraction) => chapter + fraction),
        ),
        4,
      ]) {
        await phone.evaluate((chapter) => {
          const height = document.querySelector('.depth-stage').offsetHeight,
            span = document.querySelector('.depth-story').offsetHeight - height,
            lead = height * 0.9
          scrollTo({
            top: lead + (chapter / 4) * (span - lead),
            behavior: 'instant',
          })
        }, step)
        await phone.waitForTimeout(350)
        const state = await geometry()
        records.push({
          check: 'mobile-handoff',
          requestedViewport: viewport,
          step,
          ...state,
        })
        assertGeometry(state)
        if (Number.isInteger(step)) {
          assert.equal(state.chapter, step + 1, JSON.stringify(state))
          assert.equal(state.owners.length, 1, JSON.stringify(state))
          assert.equal(state.actions.length, 2, JSON.stringify(state))
        }
      }
      await phone.screenshot({
        path: path.join(
          output,
          `mobile-motion-${viewport.width}-${viewport.height}.png`,
        ),
      })
      await phone.getByRole('button', { name: '上一幅影像', exact: true }).tap()
      await phone.waitForFunction(() =>
        document
          .querySelector('.depth-chapter p')
          .textContent.trim()
          .startsWith('04'),
      )
      await phone.getByRole('button', { name: '下一幅影像', exact: true }).tap()
      await phone.waitForFunction(() =>
        document
          .querySelector('.depth-chapter p')
          .textContent.trim()
          .startsWith('05'),
      )
    }
    await phone.setViewportSize({ width: 375, height: 667 })
    // Keep the scene inside the story when shrinking the physical viewport.
    // Starting at its old final scroll offset legitimately lands in the footer.
    await phone.evaluate(() =>
      scrollTo({
        top: document.querySelector('.depth-stage').offsetHeight * 0.9,
        behavior: 'instant',
      }),
    )
    await phone.waitForTimeout(350)
    const beforeResize = await geometry()
    records.push({
      check: 'mobile-before-viewport-height-change',
      ...beforeResize,
    })
    assertGeometry(beforeResize)
    assert.equal(beforeResize.chapter, 1, JSON.stringify(beforeResize))
    assert.equal(beforeResize.owners.length, 1, JSON.stringify(beforeResize))
    await phone.setViewportSize({ width: 375, height: 567 })
    await phone.waitForTimeout(400)
    const resized = await geometry()
    records.push({ check: 'mobile-viewport-height-change', ...resized })
    assertGeometry(resized)
    assert.equal(resized.owners.length, 1, JSON.stringify(resized))
    assert.equal(resized.actions.length, 2, JSON.stringify(resized))
    assert.deepEqual(errors, [])
    mark(
      'Real mobile touch dismisses opening without vertical capture or tilt, retains horizontal perspective, traverses five chapters both ways, and keeps card copy/actions clear in five mobile viewports',
    )
  } catch (error) {
    await phone
      .screenshot({ path: path.join(output, 'mobile-motion-failure.png') })
      .catch(() => {})
    throw error
  } finally {
    require('node:fs').writeFileSync(
      path.join(output, 'mobile-home-motion.json'),
      JSON.stringify(
        {
          records,
          errors,
          input: await phone.evaluate(() => window.__scentMotionInput),
        },
        null,
        2,
      ),
    )
    await touch.detach()
    await context.close()
  }
}

module.exports = {
  checkMemberMotion,
  checkMemberImageGestures,
  checkMobileHomeMotion,
}
