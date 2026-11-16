<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import type { Product } from '@shared/models'
import { asset, money } from '@shared/format'
import { api, addProduct, fail } from '../state'
import { imageSet, imageSource } from '../catalog-display'
const props = withDefaults(
  defineProps<{
    products: Product[]
    label: string
    kind?: 'gift' | 'objects'
    duration?: number
  }>(),
  { kind: 'gift', duration: 10000 },
)
const router = useRouter(),
  root = ref<HTMLElement>(),
  active = ref(0),
  paused = ref(false),
  hovered = ref(false),
  focused = ref(false),
  visible = ref(false),
  busy = ref<number>(),
  elapsed = ref(0),
  status = ref('')
const reduced = window.matchMedia('(prefers-reduced-motion: reduce)'),
  reduce = ref(reduced.matches),
  hidden = ref(document.hidden)
const running = computed(
  () =>
    !paused.value &&
    !hovered.value &&
    !focused.value &&
    visible.value &&
    !reduce.value &&
    !hidden.value &&
    props.products.length > 1,
)
const descriptions: Record<number, string> = {
  1021: '把白花的轻柔与青叶的清新，一起送进日常。为一段相处，留下一缕气味。',
  1014: '不自带香味的陶瓷载体，用柔和的轮廓，承接你喜欢的气息。',
  1015: '把轻雾留给空间。油液适配与使用方式，请参照商品说明。',
  1016: '以暖光陪伴慢下来的时刻。灯具与蜡烛分别销售，选购时请核对容器尺寸。',
}
let timer: ReturnType<typeof setInterval> | undefined,
  observer: IntersectionObserver | undefined,
  last = performance.now()
function show(index: number, focus = false) {
  active.value = props.products.length
    ? (index + props.products.length) % props.products.length
    : 0
  elapsed.value = 0
  last = performance.now()
  if (focus) {
    status.value = `正在展示：${props.products[active.value]?.name || ''}`
    root.value
      ?.querySelectorAll<HTMLButtonElement>('.showcase-tab')
      [active.value]?.focus({ preventScroll: true })
  }
}
function keyboard(event: KeyboardEvent) {
  if (
    !(event.target as HTMLElement).closest('.showcase-tab') ||
    !['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)
  )
    return
  event.preventDefault()
  show(
    event.key === 'Home'
      ? 0
      : event.key === 'End'
        ? props.products.length - 1
        : active.value + (event.key === 'ArrowRight' ? 1 : -1),
    true,
  )
}
function restart() {
  clearInterval(timer)
  last = performance.now()
  if (running.value)
    timer = setInterval(() => {
      const now = performance.now(),
        dt = Math.min(now - last, 500)
      last = now
      if (document.querySelector('dialog[open]')) return
      elapsed.value += dt
      if (elapsed.value >= props.duration) show(active.value + 1)
    }, 200)
}
watch(running, restart)
watch(
  () => props.products.length,
  () => {
    if (active.value >= props.products.length) show(0)
    restart()
  },
)
const media = () => {
    reduce.value = reduced.matches
  },
  visibility = () => {
    hidden.value = document.hidden
  }
async function add(product: Product) {
  if (busy.value !== undefined) return
  if (!api.token.value) {
    router.push({
      path: '/login',
      query: { return: `/products/${product.id}`, add: product.id },
    })
    return
  }
  busy.value = product.id
  try {
    await addProduct(product.id)
  } catch (failure) {
    fail(failure)
  } finally {
    busy.value = undefined
  }
}
onMounted(() => {
  observer = new IntersectionObserver(
    ([entry]) => {
      visible.value = !!entry?.isIntersecting && entry.intersectionRatio >= 0.15
    },
    { threshold: [0, 0.15, 0.5] },
  )
  if (root.value) observer.observe(root.value)
  reduced.addEventListener('change', media)
  document.addEventListener('visibilitychange', visibility)
})
onUnmounted(() => {
  clearInterval(timer)
  observer?.disconnect()
  reduced.removeEventListener('change', media)
  document.removeEventListener('visibilitychange', visibility)
})
</script>
<template>
  <div
    ref="root"
    class="showcase-shell member-stage"
    :class="{ 'is-auto-playing': running }"
    :style="{ '--stage-progress': String(elapsed / duration) }"
    role="region"
    aria-roledescription="轮播"
    :aria-label="label"
    @pointerenter="
      (event) => {
        if (event.pointerType === 'mouse') hovered = true
      }
    "
    @pointerleave="hovered = false"
    @focusin="focused = true"
    @focusout="
      (event) => {
        focused = !!root?.contains(event.relatedTarget as Node)
      }
    "
    @keydown="keyboard"
  >
    <p v-if="!products.length" class="wrap quiet">
      暂时没有可售作品。
      <RouterLink to="/catalog">查看所有作品</RouterLink>
    </p>
    <template v-else>
      <div class="showcase-track">
        <article
          v-for="(p, i) in products"
          :id="`${kind}-slide-${p.id}`"
          :key="p.id"
          class="showcase-slide"
          :class="{ 'is-active': active === i }"
          :data-stage-product="p.id"
          :aria-hidden="active !== i"
          :inert="active !== i"
        >
          <div class="showcase-copy">
            <p class="showcase-kicker">
              {{ kind === 'gift' ? '心意礼盒' : '无香器具' }} /
              {{ p.categoryName }}
            </p>
            <h3>{{ p.name }}</h3>
            <p class="showcase-description">
              {{ descriptions[p.id] || p.subtitle }}
            </p>
            <p class="showcase-spec">{{ p.spec }} / {{ p.unit }}</p>
            <p
              v-if="kind === 'gift' && p.contents"
              class="showcase-gift-contents"
            >
              {{ p.contents }}
            </p>
            <div class="showcase-commerce">
              <div class="card-purchase">
                <strong>{{ money(p.priceCents) }}</strong>
                <button
                  :disabled="busy !== undefined || p.stock < 1"
                  @click="add(p)"
                >
                  {{
                    busy === p.id
                      ? '正在加入…'
                      : p.stock < 1
                        ? '暂时售罄'
                        : '立即选购'
                  }}
                </button>
              </div>
            </div>
            <RouterLink class="showcase-detail-link" :to="`/products/${p.id}`">
              了解这件作品
              <span aria-hidden="true">↗</span>
            </RouterLink>
          </div>
          <div
            class="showcase-visual"
            :class="{ 'is-landscape': !!p.sceneImagePath }"
          >
            <RouterLink
              class="showcase-panorama-link"
              :to="`/products/${p.id}`"
              :aria-label="`查看${p.name}详情`"
            >
              <picture class="showcase-responsive-photo">
                <source
                  v-if="p.sceneImagePath"
                  media="(min-width: 961px)"
                  :srcset="asset(p.sceneImagePath)"
                />
                <img
                  class="showcase-panorama"
                  :src="
                    asset(imageSource(p.sceneImagePath || p.imagePath, 960))
                  "
                  :srcset="imageSet(p.sceneImagePath || p.imagePath)"
                  sizes="100vw"
                  :alt="`${p.name} · 全景展示图`"
                  loading="lazy"
                  decoding="async"
                />
              </picture>
            </RouterLink>
          </div>
        </article>
      </div>
      <div class="showcase-controls">
        <div class="showcase-tabs" role="group" :aria-label="`切换${label}`">
          <button
            v-for="(p, i) in products"
            :key="p.id"
            class="showcase-tab"
            :class="{ 'is-active': active === i }"
            :aria-controls="`${kind}-slide-${p.id}`"
            :aria-pressed="active === i"
            @click="show(i)"
          >
            <span>{{ String(i + 1).padStart(2, '0') }}</span>
            {{ p.name }}
            <i aria-hidden="true"></i>
          </button>
        </div>
        <div class="showcase-transport">
          <span>
            {{ String(active + 1).padStart(2, '0') }} /
            {{ String(products.length).padStart(2, '0') }}
          </span>
          <button
            :disabled="reduce || products.length < 2"
            :aria-pressed="paused"
            @click="paused = !paused"
          >
            {{ reduce ? '手动切换' : paused ? '继续轮播 ▷' : '暂停轮播 Ⅱ' }}
          </button>
        </div>
      </div>
      <span class="sr-only" aria-live="polite">{{ status }}</span>
    </template>
  </div>
</template>
