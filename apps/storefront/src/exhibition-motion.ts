import { onMounted, onUnmounted, ref, type Ref } from 'vue'

let seen = false
export function useExhibitionMotion(
  root: Ref<HTMLElement | undefined>,
  hero: Ref<HTMLElement | undefined>,
  story: Ref<HTMLElement | undefined>,
  canvas: Ref<HTMLCanvasElement | undefined>,
  count: Ref<number>,
) {
  const reduce = ref(matchMedia('(prefers-reduced-motion: reduce)').matches),
    intro = ref(!seen && !reduce.value),
    chapter = ref(0),
    storyView = ref(false),
    ended = ref(false),
    galleryVisible = ref(false)
  const media = matchMedia('(prefers-reduced-motion: reduce)')
  let x = 0,
    y = 0,
    vx = 0,
    vy = 0,
    tx = 0,
    ty = 0,
    progress = 0,
    target = 0,
    span = 1,
    height = 1,
    width = 1,
    storyHeight = 1,
    scrollPosition = 0,
    plateWidth = 1,
    plateHeight = 1,
    plateCenter = 0,
    phone = false,
    dirty = true,
    plates: HTMLElement[] = [],
    mistWidth = 0,
    mistHeight = 0,
    raf = 0,
    last = 0,
    timer: ReturnType<typeof setTimeout>,
    mistTime = 0,
    drawAt = 0,
    drag:
      | {
          id: number
          el: HTMLElement
          x: number
          y: number
          sx: number
          sy: number
          scroll: number
          width: number
          height: number
          active: boolean
        }
      | undefined,
    observer: IntersectionObserver | undefined,
    modal: MutationObserver | undefined
  const clamp = (v: number, a = -1, b = 1) => Math.max(a, Math.min(b, v))
  const fade = (value: number, start: number, end: number) => {
    const t = clamp((value - start) / (end - start), 0, 1)
    return t * t * (3 - 2 * t)
  }
  function skip() {
    clearTimeout(timer)
    intro.value = false
    schedule()
  }
  function release(event?: PointerEvent) {
    if (!drag || (event && event.pointerId !== drag.id)) return
    // Implicit touch capture moves from a child to the stage during a horizontal drag.
    if (event?.type === 'lostpointercapture' && event.target !== drag.el) return
    const previous = drag
    drag = undefined
    if (previous.el.hasPointerCapture(previous.id))
      previous.el.releasePointerCapture(previous.id)
    tx = ty = 0
    root.value?.removeAttribute('data-dragging')
    schedule()
  }
  function pointer(event: PointerEvent) {
    if (reduce.value) return
    if (drag && event.pointerId === drag.id) {
      const dx = event.clientX - drag.x,
        dy = event.clientY - drag.y
      if (!drag.active) {
        // A vertical swipe belongs entirely to native scrolling, including its inertia.
        if (Math.abs(dy) > 8 && Math.abs(dy) >= Math.abs(dx)) {
          if (intro.value) skip()
          release(event)
          return
        }
        if (Math.abs(dx) <= 8 || Math.abs(dx) < Math.abs(dy) * 1.2) return
        if (intro.value) skip()
        drag.active = true
        drag.el.setPointerCapture(event.pointerId)
        root.value?.setAttribute('data-dragging', 'true')
      }
      if (
        event.pointerType === 'mouse' &&
        Math.abs(dy) > 18 &&
        Math.abs(dy) > Math.abs(dx) * 1.15
      )
        scrollTo(0, Math.max(0, drag.scroll - dy * 1.25))
      tx = clamp(drag.sx + (dx / Math.min(drag.width, 700)) * 2.6)
      ty = clamp(drag.sy + (dy / Math.min(drag.height, 700)) * 2.2, -0.8, 0.8)
    } else if (event.pointerType === 'mouse' && !intro.value) {
      const r = (event.currentTarget as HTMLElement).getBoundingClientRect()
      tx = clamp(((event.clientX - r.left) / r.width) * 2 - 1) * 0.5
      ty = clamp(((event.clientY - r.top) / r.height) * 2 - 1) * 0.4
    }
    schedule()
  }
  function down(event: PointerEvent) {
    if (
      event.button !== 0 ||
      reduce.value ||
      (intro.value && event.pointerType === 'mouse') ||
      (event.target as HTMLElement).closest('a,button,input')
    )
      return
    const el = event.currentTarget as HTMLElement,
      r = el.getBoundingClientRect(),
      active = event.pointerType === 'mouse'
    drag = {
      id: event.pointerId,
      el,
      x: event.clientX,
      y: event.clientY,
      sx: x,
      sy: y,
      scroll: scrollY,
      width: r.width,
      height: r.height,
      active,
    }
    if (active) {
      el.setPointerCapture(event.pointerId)
      root.value?.setAttribute('data-dragging', 'true')
      event.preventDefault()
    }
  }
  function leave() {
    if (!drag) {
      tx = ty = 0
      schedule()
    }
  }
  function key(event: KeyboardEvent) {
    const poses: Record<string, [number, number]> = {
      ArrowLeft: [-0.9, 0],
      ArrowRight: [0.9, 0],
      ArrowUp: [0, -0.7],
      ArrowDown: [0, 0.7],
      Home: [0, 0],
    }
    if (!poses[event.key] || reduce.value) return
    event.preventDefault()
    ;[tx, ty] = poses[event.key]!
    schedule()
  }
  function measure() {
    plates = [
      ...(story.value?.querySelectorAll<HTMLElement>('.depth-plate') || []),
    ]
    // CSS uses the stable small viewport; the browser toolbar must not remap the story.
    height = hero.value?.offsetHeight || innerHeight
    width = root.value?.clientWidth || innerWidth
    storyHeight = story.value?.offsetHeight || height
    span = Math.max(1, storyHeight - height)
    phone = matchMedia(
      '(max-width: 760px), (hover: none) and (max-height: 620px)',
    ).matches
    const first = plates[0]
    if (first) {
      for (const key of ['width', 'height', 'border-radius', 'top'])
        first.style.removeProperty(key)
      plateWidth = first.offsetWidth
      plateHeight = first.offsetHeight
      plateCenter = first.offsetTop
    }
    dirty = true
    scroll()
    resizeMist()
  }
  function scroll() {
    scrollPosition = scrollY
    if (intro.value && scrollPosition > 1) skip()
    target = clamp(scrollPosition / span, 0, 1)
    storyView.value = scrollPosition > height * 0.45
    ended.value = scrollPosition > storyHeight - height * 0.4
    if (reduce.value) paint()
    schedule()
  }
  function paint() {
    const node = root.value
    if (!node) return
    node.style.setProperty('--exp-x', String(x))
    node.style.setProperty('--exp-y', String(y))
    if (reduce.value) {
      hero.value?.style.removeProperty('opacity')
      plates.forEach((plate) => {
        plate.inert = false
        plate.removeAttribute('aria-hidden')
        plate.style.removeProperty('--plate-ink')
      })
      return
    }
    const h = height,
      reveal = clamp((progress * span) / (h * 0.88), 0, 1),
      smooth = reveal * reveal * (3 - 2 * reveal),
      step =
        clamp((progress * span - h * 0.9) / Math.max(1, span - h * 0.9), 0, 1) *
        Math.max(0, count.value - 1)
    node.style.setProperty('--story-reveal', String(smooth))
    if (hero.value) {
      hero.value.style.opacity = String(1 - smooth)
      hero.value.style.pointerEvents = reveal > 0.92 ? 'none' : 'auto'
    }
    story.value?.classList.toggle('story-engaged', reveal > 0.7)
    const base = Math.min(Math.max(0, count.value - 1), Math.floor(step)),
      handoff = step - base
    chapter.value = Math.min(
      Math.max(0, count.value - 1),
      base + (handoff >= 0.76 ? 1 : 0),
    )
    plates.forEach((plate, i) => {
      const phase = step - i + 1
      let py = 0,
        rx = 0,
        rz = 0,
        z = 0,
        scale = 1,
        opacity = 1
      if (phase < 1) {
        // Leave a reading interval before the next image rises into the copy area.
        const t = clamp((phase - 0.3) / 0.7, 0, 1),
          ease = 1 - Math.pow(1 - t, 3)
        py = (1 - ease) * h * 0.98
        rx = (1 - ease) * 56
        rz = (1 - ease) * (i % 2 ? -5 : 5)
        z = -240 * (1 - ease)
        scale = 0.9 + 0.1 * ease
        opacity = clamp(t * 4, 0, 1)
      } else {
        const past = phase - 1
        py = -past * h * 0.035
        rx = -Math.min(18, past * 5)
        rz = (i % 2 ? -1 : 1) * Math.min(2.5, past)
        z = -past * 160
        scale = 1 - Math.min(0.12, past * 0.036)
      }
      if (i === 0 && reveal < 1) {
        if (phone) {
          // Crop a uniformly scaled photograph instead of changing its copy layout every frame.
          scale *=
            1 +
            (Math.max(width / plateWidth, h / plateHeight) - 1) * (1 - smooth)
          py += (h / 2 - plateCenter) * (1 - smooth)
        } else {
          const w = plateWidth,
            ph = plateHeight
          plate.style.width = width + (w - width) * smooth + 'px'
          plate.style.height = h + (ph - h) * smooth + 'px'
          plate.style.borderRadius = smooth * 30 + 'px'
          plate.style.top = 50 + smooth * 3 + '%'
        }
        opacity = clamp(reveal * 4, 0, 1)
      } else
        for (const k of ['width', 'height', 'border-radius', 'top'])
          plate.style.removeProperty(k)
      plate.style.transform = `translate3d(calc(-50% + ${x * (i + 1) * 3 * smooth}px),calc(-50% + ${py}px),${z}px) rotateX(${rx - y * 1.6 * smooth}deg) rotateY(${x * 5 * smooth}deg) rotateZ(${rz}deg) scale(${scale})`
      plate.style.opacity = String(opacity)
      // Text ownership changes only once the incoming image is almost settled.
      // Previous text disappears before the image can cover its actions.
      const ink =
        (i === base
          ? 1 - fade(handoff, 0.34, 0.44)
          : i === base + 1
            ? fade(handoff, 0.76, 0.9)
            : 0) * fade(reveal, 0.65, 1)
      plate.style.setProperty('--plate-ink', String(ink))
      const inert = i !== chapter.value || ink < 0.98 || reveal < 0.98
      if (plate.inert !== inert) plate.inert = inert
      if (plate.getAttribute('aria-hidden') !== String(inert))
        plate.setAttribute('aria-hidden', String(inert))
    })
  }
  function resizeMist() {
    const c = canvas.value
    if (!c) return
    const r = c.getBoundingClientRect(),
      dpr = Math.min(devicePixelRatio || 1, phone ? 1 : 1.5)
    mistWidth = r.width
    mistHeight = r.height
    c.width = Math.round(r.width * dpr)
    c.height = Math.round(r.height * dpr)
    c.getContext('2d')?.setTransform(dpr, 0, 0, dpr, 0, 0)
  }
  function mist() {
    const c = canvas.value,
      ctx = c?.getContext('2d')
    if (!c || !ctx) return
    const w = mistWidth,
      h = mistHeight
    ctx.clearRect(0, 0, w, h)
    ctx.globalCompositeOperation = 'screen'
    for (let i = 0; i < 7; i++) {
      const phase = mistTime * 0.055 + i * 0.9,
        r = w * (0.13 + 0.02 * Math.sin(phase * 0.7))
      ctx.save()
      ctx.translate(
        w * (0.31 + 0.055 * Math.sin(phase)),
        h * (0.7 - 0.35 * ((mistTime * 0.012 + i * 0.137) % 1)),
      )
      ctx.scale(0.55, 1.8)
      const g = ctx.createRadialGradient(0, 0, 0, 0, 0, r)
      g.addColorStop(0, 'rgba(218,222,225,.024)')
      g.addColorStop(0.45, 'rgba(218,222,225,.011)')
      g.addColorStop(1, 'rgba(218,222,225,0)')
      ctx.fillStyle = g
      ctx.beginPath()
      ctx.arc(0, 0, r, 0, Math.PI * 2)
      ctx.fill()
      ctx.restore()
    }
    ctx.globalCompositeOperation = 'source-over'
  }
  function blocked() {
    return (
      document.hidden ||
      !!document.querySelector('dialog[open],.advisor-panel,.category-menu')
    )
  }
  function tick(now: number) {
    raf = 0
    if (reduce.value || blocked()) return
    const dt = last ? Math.min((now - last) / 1000, 0.032) : 0.016
    last = now
    const previous = progress,
      previousX = x,
      previousY = y
    vx += ((tx - x) * 48 - vx * 13) * dt
    vy += ((ty - y) * 48 - vy * 13) * dt
    x += vx * dt
    y += vy * dt
    if (
      Math.abs(tx - x) + Math.abs(ty - y) + Math.abs(vx) + Math.abs(vy) <
      0.002
    ) {
      x = tx
      y = ty
      vx = vy = 0
    }
    progress = phone
      ? target
      : progress + (target - progress) * (1 - Math.exp(-dt * 17))
    if (Math.abs(target - progress) < 0.00001) progress = target
    if (dirty || progress !== previous || x !== previousX || y !== previousY) {
      paint()
      dirty = false
    }
    if (
      !intro.value &&
      scrollPosition < height &&
      now - drawAt > (phone ? 60 : 40)
    ) {
      mistTime += dt
      mist()
      drawAt = now
    }
    if (
      (!intro.value && scrollPosition < height) ||
      Math.abs(target - progress) > 0.0002 ||
      Math.abs(tx - x) + Math.abs(ty - y) + Math.abs(vx) + Math.abs(vy) > 0.002
    )
      raf = requestAnimationFrame(tick)
  }
  function schedule() {
    if (!raf && !reduce.value && !blocked()) {
      last = 0
      raf = requestAnimationFrame(tick)
    }
  }
  function mediaChange() {
    reduce.value = media.matches
    release()
    if (reduce.value) {
      skip()
      x = y = vx = vy = tx = ty = 0
      root.value?.style.setProperty('--exp-x', '0')
      root.value?.style.setProperty('--exp-y', '0')
      story.value
        ?.querySelectorAll<HTMLElement>('.depth-plate')
        .forEach((p) => {
          p.inert = false
          p.removeAttribute('aria-hidden')
        })
      canvas.value
        ?.getContext('2d')
        ?.clearRect(0, 0, canvas.value.width, canvas.value.height)
    }
    measure()
  }
  function next(index: number) {
    const lead = height * 0.9
    window.scrollTo({
      top:
        lead +
        (clamp(index, 0, count.value - 1) / Math.max(1, count.value - 1)) *
          (span - lead),
      behavior: reduce.value ? 'auto' : 'smooth',
    })
  }
  onMounted(() => {
    seen = true
    if (intro.value) timer = setTimeout(skip, 3400)
    window.addEventListener('scroll', scroll, { passive: true })
    window.addEventListener('resize', measure)
    document.addEventListener('visibilitychange', schedule)
    media.addEventListener('change', mediaChange)
    observer = new IntersectionObserver((entries) => {
      for (const e of entries) galleryVisible.value = e.isIntersecting
    })
    const gallery = root.value?.querySelector('.floating-collection')
    if (gallery) observer.observe(gallery)
    modal = new MutationObserver(schedule)
    modal.observe(document.body, {
      subtree: true,
      attributes: true,
      attributeFilter: ['open'],
      childList: true,
    })
    measure()
  })
  onUnmounted(() => {
    release()
    cancelAnimationFrame(raf)
    clearTimeout(timer)
    observer?.disconnect()
    modal?.disconnect()
    window.removeEventListener('scroll', scroll)
    window.removeEventListener('resize', measure)
    document.removeEventListener('visibilitychange', schedule)
    media.removeEventListener('change', mediaChange)
  })
  return {
    reduce,
    intro,
    chapter,
    storyView,
    ended,
    galleryVisible,
    skip,
    pointer,
    down,
    release,
    leave,
    key,
    next,
    measure,
  }
}
