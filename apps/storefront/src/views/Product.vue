<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { asset, money } from '@shared/format'
import { errorText } from '@shared/api'
import type { ProductDetail } from '@shared/models'
import { addProduct, api, fail } from '../state'
import State from '../components/State.vue'
import ProductCard from '../components/ProductCard.vue'
import Reviews from '../components/Reviews.vue'
import { imageSource, imageSet, productDescription } from '../catalog-display'
const route = useRoute(),
  router = useRouter(),
  data = ref<ProductDetail>(),
  loading = ref(false),
  error = ref(''),
  busy = ref(false)
const gallery = ref<'imagePath' | 'scentImagePath' | 'sceneImagePath'>(
  'imagePath',
)
const currentImage = computed(
  () => p.value?.[gallery.value] || p.value?.imagePath || '',
)
const p = computed(() => data.value?.product)
const galleryKeys = computed(() =>
  (['imagePath', 'scentImagePath', 'sceneImagePath'] as const).filter(
    (key) => p.value?.[key],
  ),
)
let touch: { x: number; y: number; id: number } | undefined
function stepGallery(direction: number) {
  const keys = galleryKeys.value
  if (keys.length)
    gallery.value =
      keys[
        (keys.indexOf(gallery.value) + direction + keys.length) % keys.length
      ]!
}
function galleryStart(event: PointerEvent) {
  if (
    event.pointerType !== 'mouse' &&
    !(event.target as HTMLElement).closest('button')
  )
    touch = { x: event.clientX, y: event.clientY, id: event.pointerId }
}
function galleryEnd(event: PointerEvent) {
  if (!touch || touch.id !== event.pointerId) return
  const dx = event.clientX - touch.x,
    dy = event.clientY - touch.y
  touch = undefined
  if (Math.abs(dx) > 45 && Math.abs(dx) > Math.abs(dy) * 1.4)
    stepGallery(dx < 0 ? 1 : -1)
}
const copy = computed(() =>
  productDescription(p.value?.description || '', p.value?.subtitle || ''),
)
let generation = 0
async function load() {
  const current = ++generation
  loading.value = true
  error.value = ''
  data.value = undefined
  gallery.value = 'imagePath'
  try {
    const result = await api.request<ProductDetail>(
      `/catalog/products/${route.params.id}`,
      {},
      false,
    )
    if (current === generation) {
      data.value = result
      if (route.path === `/products/${result.product.id}`)
        document.title = `${result.product.name} · SCENT`
    }
  } catch (e) {
    if (current === generation) error.value = errorText(e)
  } finally {
    if (current === generation) loading.value = false
  }
}
async function add() {
  if (!p.value || busy.value) return
  if (!api.token.value) {
    router.push({
      path: '/login',
      query: { return: `/products/${p.value.id}`, add: p.value.id },
    })
    return
  }
  busy.value = true
  try {
    await addProduct(p.value.id)
  } catch (e) {
    fail(e)
  } finally {
    busy.value = false
  }
}
watch(() => route.params.id, load, { immediate: true })
</script>
<template>
  <div class="wrap page">
    <RouterLink class="text-link breadcrumb" to="/catalog">
      ← 所有作品
    </RouterLink>
    <State :loading="loading" :error="error" @retry="load" />
    <div v-if="p && !loading" class="product-detail">
      <div
        class="detail-image"
        tabindex="0"
        role="region"
        aria-label="商品图片浏览"
        @keydown.left.prevent="stepGallery(-1)"
        @keydown.right.prevent="stepGallery(1)"
        @pointerdown="galleryStart"
        @pointerup="galleryEnd"
        @pointercancel="touch = undefined"
      >
        <img
          :class="{ landscape: gallery === 'sceneImagePath' }"
          :src="asset(imageSource(currentImage, 960))"
          :srcset="imageSet(currentImage)"
          sizes="(max-width: 760px) calc(100vw - 40px), 46vw"
          :alt="
            gallery === 'scentImagePath'
              ? `${p.name}香调意象（非配方或成分图）`
              : p.name
          "
          decoding="async"
        />
        <div class="gallery-tabs" role="group" aria-label="商品照片">
          <button
            :aria-pressed="gallery === 'imagePath'"
            @click="gallery = 'imagePath'"
          >
            商品图
          </button>
          <button
            v-if="p.scentImagePath"
            :aria-pressed="gallery === 'scentImagePath'"
            @click="gallery = 'scentImagePath'"
          >
            香调意象
          </button>
          <button
            v-if="p.sceneImagePath"
            :aria-pressed="gallery === 'sceneImagePath'"
            @click="gallery = 'sceneImagePath'"
          >
            场景图
          </button>
        </div>
        <span v-if="p.imagePath.includes('/types/')" class="image-caption">
          形态示意 · 非实拍
        </span>
      </div>
      <div class="detail-copy">
        <p class="eyebrow">
          {{ p.familyName || '无香器具' }} / {{ p.categoryName }}
        </p>
        <h1>{{ p.name }}</h1>
        <p class="subtitle">{{ p.subtitle }}</p>
        <p class="detail-price">
          {{ money(p.priceCents) }}
          <span>{{ p.spec }} / {{ p.unit }}</span>
        </p>
        <div v-if="copy.paragraphs.length" class="description">
          <p
            v-for="(paragraph, index) in copy.paragraphs"
            :key="index"
            class="preserve"
          >
            {{ paragraph }}
          </p>
        </div>
        <button
          class="btn full"
          :disabled="busy || !p.active || p.stock < 1"
          @click="add"
        >
          {{
            busy
              ? '正在加入…'
              : !p.active
                ? '已下架'
                : p.stock < 1
                  ? '暂时售罄'
                  : '加入购物车'
          }}
        </button>
        <p class="quiet">运费由 SCENT 承担 · 固定规格销售</p>
        <details open>
          <summary>使用与材质</summary>
          <p class="preserve">{{ p.usageNotes || '请按商品说明使用。' }}</p>
          <p v-if="p.material">材质：{{ p.material }}</p>
          <p v-if="p.powerInfo">供电：{{ p.powerInfo }}</p>
        </details>
        <details v-if="p.contents" open>
          <summary>礼盒内容</summary>
          <p class="preserve">{{ p.contents }}</p>
        </details>
        <aside
          v-if="copy.notices.length"
          class="detail-disclosure"
          aria-label="教学商品说明"
        >
          <p v-for="(notice, index) in copy.notices" :key="index">
            {{ notice }}
          </p>
        </aside>
      </div>
    </div>
    <section v-if="data?.compatibleProducts.length" class="section">
      <div class="section-head">
        <h2>适配器物</h2>
        <span class="quiet">按商品明确标注的适配关系选购</span>
      </div>
      <div class="product-grid">
        <ProductCard
          v-for="x in data.compatibleProducts"
          :key="x.id"
          :product="x"
        />
      </div>
    </section>
    <Reviews v-if="p && !loading" :product-id="p.id" />
  </div>
</template>
