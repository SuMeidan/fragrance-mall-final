<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import type { Product, CategoryTree } from '@shared/models'
import { asset, money } from '@shared/format'
import { errorText } from '@shared/api'
import { catalog } from '../state'
import { publicProducts } from '../public-catalog'
import { imageSource } from '../catalog-display'
import { useExhibitionMotion } from '../exhibition-motion'
import State from '../components/State.vue'
import heroImage from '../assets/refined/hero.webp'
const products = ref<Product[]>([]),
  categories = ref<CategoryTree[]>([]),
  loading = ref(true),
  error = ref(''),
  root = ref<HTMLElement>(),
  hero = ref<HTMLElement>(),
  story = ref<HTMLElement>(),
  canvas = ref<HTMLCanvasElement>()
const preferences = [1001, 2026, 2031, 2037, 2051],
  lines = [
    '让日常，慢一点。',
    '一路，带着喜欢的气息。',
    '一缕沉静，缓缓展开。',
    '气味，也需要一个容器。',
    '把心意，轻轻放好。',
  ]
const plates = computed(() =>
  categories.value
    .map((category, i) => {
      const members = products.value.filter((p) =>
          category.children.some((c) => c.id === p.categoryId),
        ),
        product = members.find((p) => p.id === preferences[i]) || members[0]
      return product
        ? { category, product, line: lines[i] || '让无形，成为风格。' }
        : null
    })
    .filter((p) => p !== null),
)
const count = computed(() => plates.value.length),
  motion = useExhibitionMotion(root, hero, story, canvas, count)
const { intro, chapter, storyView, ended, galleryVisible, reduce } = motion
const rows = computed(() =>
  [0, 1, 2].map((row) => products.value.filter((_, i) => i % 3 === row)),
)
async function load() {
  loading.value = true
  error.value = ''
  try {
    const [meta, items] = await Promise.all([catalog(), publicProducts()])
    categories.value = meta.categories
    products.value = items
    await nextTick()
    motion.measure()
  } catch (e) {
    error.value = errorText(e)
  } finally {
    loading.value = false
    nextTick(motion.measure)
  }
}
function photo(p: Product) {
  return asset(imageSource(p.imagePath, 480))
}
function focusTile(event: FocusEvent) {
  const target = (event.target as HTMLElement).closest<HTMLElement>(
      '.floating-product',
    ),
    track = target?.closest<HTMLElement>('.floating-track')
  if (!target || !track || reduce.value) return
  const matrix = new DOMMatrixReadOnly(getComputedStyle(track).transform),
    r = target.getBoundingClientRect(),
    delta =
      r.left < 24
        ? 24 - r.left
        : r.right > innerWidth - 24
          ? innerWidth - 24 - r.right
          : 0
  track.style.animation = 'none'
  track.style.transform = `translate3d(${matrix.m41 + delta}px,0,0)`
}
function blurLane(event: FocusEvent) {
  const lane = event.currentTarget as HTMLElement
  setTimeout(() => {
    if (!lane.contains(document.activeElement)) {
      const track = lane.querySelector<HTMLElement>('.floating-track')
      track?.style.removeProperty('animation')
      track?.style.removeProperty('transform')
    }
  }, 0)
}
onMounted(load)
</script>
<template>
  <div
    ref="root"
    class="exhibition-home"
    :class="{
      'story-view': storyView,
      'story-ended': ended,
      'opening-active': intro,
    }"
  >
    <div v-if="intro" class="center-opening playing" aria-hidden="true">
      <div
        v-for="side in ['left', 'right']"
        :key="side"
        class="opening-wing"
        :class="`opening-${side}`"
      >
        <div class="curtain-word">
          <span v-for="(letter, i) in 'SCENT'" :key="i" :style="{ '--i': i }">
            {{ letter }}
          </span>
        </div>
        <p class="curtain-tagline">让无形，成为风格。</p>
      </div>
      <div class="opening-seam" />
    </div>
    <button v-if="intro" class="opening-skip" @click="motion.skip">
      跳过开场
    </button>
    <section
      ref="hero"
      class="film-hero"
      aria-label="SCENT 品牌影像"
      @pointermove="motion.pointer"
      @pointerdown="motion.down"
      @pointerup="motion.release"
      @pointercancel="motion.release"
      @lostpointercapture="motion.release"
      @pointerleave="motion.leave"
      @dragstart.prevent
    >
      <div class="film-depth-plane">
        <div class="film-visual">
          <div class="film-frame">
            <img
              :src="heroImage"
              alt="光影中的 SCENT 陶瓷香薰蜡烛"
              fetchpriority="high"
            />
          </div>
        </div>
      </div>
      <canvas ref="canvas" class="refine-mist" id="mist" aria-hidden="true" />
      <div class="experience-light" aria-hidden="true" />
      <div class="film-copy">
        <div
          class="experience-letter-stage"
          tabindex="0"
          role="group"
          aria-label="SCENT立体字标，可拖动或使用方向键"
          @keydown="motion.key"
        >
          <h1 class="experience-brand" aria-label="SCENT">
            <span
              v-for="(letter, i) in 'SCENT'"
              :key="i"
              aria-hidden="true"
              :style="{
                '--depth': [12, 88, 180, 88, 12][i],
                '--spread': (i - 2) * 4,
              }"
            >
              {{ letter }}
            </span>
          </h1>
        </div>
        <p class="experience-slogan">让无形，成为风格。</p>
      </div>
    </section>
    <section ref="story" class="depth-story" aria-label="五种香氛生活">
      <div
        class="depth-stage"
        @pointermove="motion.pointer"
        @pointerdown="motion.down"
        @pointerup="motion.release"
        @pointercancel="motion.release"
        @lostpointercapture="motion.release"
        @pointerleave="motion.leave"
        @dragstart.prevent
      >
        <div class="depth-back-brand" aria-hidden="true">SCENT</div>
        <div class="depth-chapter">
          <span>香氛生活 / 五种表达</span>
          <p aria-live="polite">
            {{ String(chapter + 1).padStart(2, '0') }} /
            {{ plates[chapter]?.category.name }}
          </p>
        </div>
        <div class="depth-world">
          <article
            v-for="(plate, i) in plates"
            :key="plate.product.id"
            class="depth-plate"
            :class="{ 'depth-first': i === 0 }"
            :data-category="plate.category.id"
            :style="{ zIndex: i + 1 }"
            :inert="!reduce"
            :aria-label="`${plate.category.name} · ${plate.product.name}`"
          >
            <img
              :src="
                i === 0 && plate.product.id === 1001
                  ? heroImage
                  : photo(plate.product)
              "
              :alt="`${plate.product.name} · ${plate.product.categoryName}`"
              decoding="async"
            />
            <div class="plate-shade" />
            <div class="plate-copy">
              <span class="plate-number">
                {{ String(i + 1).padStart(2, '0') }} /
                {{ String(count).padStart(2, '0') }} · {{ plate.category.name }}
              </span>
              <h2>{{ plate.product.name }}</h2>
              <p>{{ plate.line }}</p>
              <span class="plate-spec">
                {{ plate.product.categoryName }} · {{ plate.product.spec }} /
                {{ plate.product.unit }}
              </span>
              <span class="plate-price">
                {{ money(plate.product.priceCents) }}
              </span>
              <RouterLink :to="`/products/${plate.product.id}`">
                认识这件作品
                <span aria-hidden="true">↗</span>
              </RouterLink>
              <RouterLink
                class="plate-category-link"
                :to="`/catalog?categoryId=${plate.category.id}`"
              >
                探索{{ plate.category.name }} →
              </RouterLink>
            </div>
            <span class="plate-signature" aria-hidden="true">SCENT</span>
          </article>
        </div>
        <div v-if="count" class="depth-controls">
          <button
            :disabled="chapter === 0"
            aria-label="上一幅影像"
            @click="motion.next(chapter - 1)"
          >
            ‹
          </button>
          <span class="depth-progress" aria-hidden="true">
            <i
              v-for="(_, i) in plates"
              :key="i"
              :class="{ active: i === chapter }"
            />
          </span>
          <button
            :disabled="chapter === count - 1"
            aria-label="下一幅影像"
            @click="motion.next(chapter + 1)"
          >
            ›
          </button>
        </div>
      </div>
    </section>
    <div v-if="loading || error" class="wrap">
      <State :loading="loading" :error="error" @retry="load" />
    </div>
    <footer class="experience-end">
      <section
        class="floating-collection"
        aria-labelledby="floating-title"
        :class="{ 'float-paused': !galleryVisible }"
      >
        <div class="floating-heading">
          <div>
            <span class="plate-number">THE COLLECTION / 全部作品</span>
            <h2 id="floating-title">总有一种，与你相遇。</h2>
          </div>
        </div>
        <div class="floating-rows">
          <div
            v-for="(row, i) in rows"
            :key="i"
            class="floating-lane"
            :aria-label="`商品影像第${i + 1}行`"
            @focusin="focusTile"
            @focusout="blurLane"
          >
            <div
              class="floating-track"
              :style="{
                '--lap-seconds': row.length * (i === 1 ? 11 : 13) + 's',
              }"
            >
              <div
                v-for="copy in [0, 1]"
                :key="copy"
                class="floating-group"
                :aria-hidden="copy === 1 ? true : undefined"
              >
                <RouterLink
                  v-for="p in row"
                  :key="p.id"
                  class="floating-product"
                  :tabindex="copy === 1 ? -1 : undefined"
                  :to="`/products/${p.id}`"
                  :aria-label="`${p.name}，${money(p.priceCents)}`"
                >
                  <img :src="photo(p)" alt="" loading="lazy" decoding="async" />
                  <span>
                    {{ p.name }}
                    <small>{{ money(p.priceCents) }}</small>
                  </span>
                </RouterLink>
              </div>
            </div>
          </div>
        </div>
        <div class="floating-close">
          <span>SCENT</span>
          <RouterLink to="/catalog">
            探索全部 {{ products.length }} 件商品 ↗
          </RouterLink>
        </div>
      </section>
    </footer>
    <p class="experience-hint">向上滑动，展开下一层。</p>
    <RouterLink class="experience-entry" to="/catalog">
      探索全部商品
      <span aria-hidden="true">↗</span>
    </RouterLink>
  </div>
</template>
