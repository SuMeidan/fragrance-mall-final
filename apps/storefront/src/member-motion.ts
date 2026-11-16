import { computed, onMounted, onUnmounted, ref, watch, type Ref } from 'vue'

// Timing and mist drawing follow the member's final home.js; lifecycle belongs to Vue.
let openingSeen = false
export function useMemberMotion(
  canvas: Ref<HTMLCanvasElement | undefined>,
  sceneCount: Ref<number>,
  hero: Ref<HTMLElement | undefined>,
) {
  const reduced = window.matchMedia('(prefers-reduced-motion: reduce)')
  const reduce = ref(reduced.matches),
    paused = ref(false),
    active = ref(0),
    intro = ref(!openingSeen && !reduce.value),
    replayKey = ref(0),
    visible = ref(true),
    hidden = ref(document.hidden),
    modal = ref(false)
  const running = computed(
    () =>
      !reduce.value &&
      !paused.value &&
      visible.value &&
      !hidden.value &&
      !modal.value,
  )
  let frame = 0,
    introTimer: ReturnType<typeof setTimeout> | undefined,
    observer: IntersectionObserver | undefined,
    dialogs: MutationObserver | undefined
  let last = 0,
    elapsed = 0,
    time = 0,
    width = 1,
    height = 1,
    drawAt = 0
  function select(index: number, focus = false) {
    active.value = sceneCount.value
      ? (index + sceneCount.value) % sceneCount.value
      : 0
    elapsed = 0
    if (focus)
      hero.value
        ?.querySelectorAll<HTMLButtonElement>('.scent-tab')
        [active.value]?.focus({ preventScroll: true })
  }
  function skip() {
    clearTimeout(introTimer)
    intro.value = false
  }
  function replay() {
    if (reduce.value) {
      select(0)
      return
    }
    paused.value = false
    select(0)
    replayKey.value++
    intro.value = true
    clearTimeout(introTimer)
    introTimer = setTimeout(skip, 2600)
    window.scrollTo({ top: 0, behavior: 'smooth' })
  }
  function resize() {
    const node = canvas.value,
      ctx = node?.getContext('2d')
    if (!node || !ctx) return
    const box = node.getBoundingClientRect(),
      dpr = Math.min(window.devicePixelRatio || 1, 1.5)
    width = box.width
    height = box.height
    node.width = Math.round(width * dpr)
    node.height = Math.round(height * dpr)
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  }
  function draw() {
    const ctx = canvas.value?.getContext('2d')
    if (!ctx) return
    ctx.clearRect(0, 0, width, height)
    ctx.globalCompositeOperation = 'screen'
    for (let i = 0; i < 7; i++) {
      const phase = time * 0.055 + i * 0.9,
        x = width * (0.31 + 0.055 * Math.sin(phase)),
        y = height * (0.7 - 0.35 * ((time * 0.012 + i * 0.137) % 1)),
        radius = width * (0.13 + 0.02 * Math.sin(phase * 0.7))
      ctx.save()
      ctx.translate(x, y)
      ctx.scale(0.55, 1.8)
      const gradient = ctx.createRadialGradient(0, 0, 0, 0, 0, radius)
      gradient.addColorStop(0, 'rgba(218,222,225,0.024)')
      gradient.addColorStop(0.45, 'rgba(218,222,225,0.011)')
      gradient.addColorStop(1, 'rgba(218,222,225,0)')
      ctx.fillStyle = gradient
      ctx.beginPath()
      ctx.arc(0, 0, radius, 0, Math.PI * 2)
      ctx.fill()
      ctx.restore()
    }
    ctx.globalCompositeOperation = 'source-over'
  }
  function tick(now: number) {
    if (running.value) {
      const dt = last ? Math.min(now - last, 100) : 0
      elapsed += dt
      time += dt / 1000
      if (sceneCount.value > 1 && elapsed >= 12000) select(active.value + 1)
      if (!drawAt || now - drawAt >= 40) {
        draw()
        drawAt = now
      }
    }
    last = now
    frame = requestAnimationFrame(tick)
  }
  const media = () => {
    reduce.value = reduced.matches
    if (reduce.value) {
      skip()
      canvas.value?.getContext('2d')?.clearRect(0, 0, width, height)
    }
  }
  const visibility = () => {
    hidden.value = document.hidden
    last = 0
  }
  watch(running, (value) => {
    cancelAnimationFrame(frame)
    last = 0
    if (value) frame = requestAnimationFrame(tick)
  })
  watch(sceneCount, () => {
    if (active.value >= sceneCount.value) select(0)
  })
  onMounted(() => {
    openingSeen = true
    if (intro.value) introTimer = setTimeout(skip, 2600)
    reduced.addEventListener('change', media)
    document.addEventListener('visibilitychange', visibility)
    window.addEventListener('resize', resize, { passive: true })
    observer = new IntersectionObserver(([entry]) => {
      visible.value = !!entry?.isIntersecting
    })
    if (hero.value) observer.observe(hero.value)
    dialogs = new MutationObserver(() => {
      modal.value = !!document.querySelector('dialog[open]')
    })
    dialogs.observe(document.body, {
      subtree: true,
      attributes: true,
      attributeFilter: ['open'],
    })
    resize()
    frame = requestAnimationFrame(tick)
  })
  onUnmounted(() => {
    cancelAnimationFrame(frame)
    clearTimeout(introTimer)
    observer?.disconnect()
    dialogs?.disconnect()
    reduced.removeEventListener('change', media)
    document.removeEventListener('visibilitychange', visibility)
    window.removeEventListener('resize', resize)
  })
  return {
    active,
    paused,
    reduce,
    running,
    intro,
    replayKey,
    replay,
    skip,
    select,
  }
}
