<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { Product } from '@shared/models'
import { asset, money } from '@shared/format'
import { api, addProduct, fail } from '../state'
import { imageSet, imageSource } from '../catalog-display'
const props = defineProps<{ product: Product }>()
const router = useRouter(),
  active = ref(0),
  busy = ref(false)
const images = computed(() => [
  { label: '商品图', path: props.product.imagePath },
  ...(props.product.scentImagePath
    ? [{ label: '香调意象', path: props.product.scentImagePath }]
    : []),
])
let pointer: { x: number; y: number; id: number } | undefined,
  suppressUntil = 0
function show(index: number) {
  active.value = (index + images.value.length) % images.value.length
}
function enter(event: PointerEvent) {
  if (event.pointerType === 'mouse' && images.value.length > 1) show(1)
}
function leave(event: PointerEvent) {
  if (event.pointerType === 'mouse') show(0)
}
function start(event: PointerEvent) {
  if (
    event.pointerType !== 'mouse' &&
    !(event.target as HTMLElement).closest('button')
  )
    pointer = { x: event.clientX, y: event.clientY, id: event.pointerId }
}
function end(event: PointerEvent) {
  if (!pointer || pointer.id !== event.pointerId) return
  const dx = event.clientX - pointer.x,
    dy = event.clientY - pointer.y
  pointer = undefined
  if (Math.abs(dx) > 45 && Math.abs(dx) > Math.abs(dy) * 1.4) {
    show(active.value + (dx < 0 ? 1 : -1))
    suppressUntil = Date.now() + 500
  }
}
function click(event: MouseEvent) {
  if (Date.now() < suppressUntil) {
    event.preventDefault()
    event.stopPropagation()
  }
}
async function add() {
  if (busy.value) return
  if (!api.token.value) {
    router.push({
      path: '/login',
      query: { return: `/products/${props.product.id}`, add: props.product.id },
    })
    return
  }
  busy.value = true
  try {
    await addProduct(props.product.id)
  } catch (error) {
    fail(error)
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <article
    class="product-card member-reference-card"
    :data-product-card="product.id"
  >
    <div
      class="image-carousel"
      role="region"
      :aria-label="`${product.name}图片`"
      @keydown.right.prevent="show(active + 1)"
      @keydown.left.prevent="show(active - 1)"
      @pointerdown="start"
      @pointerup="end"
      @pointercancel="pointer = undefined"
      @click.capture="click"
    >
      <RouterLink
        class="carousel-photo-link"
        :to="`/products/${product.id}`"
        :aria-label="`查看${product.name}详情`"
        @pointerenter="enter"
        @pointerleave="leave"
      >
        <img
          v-for="(image, i) in images"
          :key="image.path"
          class="carousel-slide"
          :class="{ 'is-current': active === i }"
          :src="asset(imageSource(image.path, 480))"
          :srcset="imageSet(image.path)"
          sizes="(max-width: 600px) 86vw, 28vw"
          :alt="`${product.name} · ${image.label}`"
          :aria-hidden="active !== i"
          loading="lazy"
          decoding="async"
        />
      </RouterLink>
      <div v-if="images.length > 1" class="carousel-caption">
        <span>{{ images[active]?.label }}</span>
        <div class="carousel-dots" role="group" aria-label="图片选择">
          <button
            v-for="(image, i) in images"
            :key="image.label"
            class="carousel-dot"
            :class="{ 'is-current': active === i }"
            :aria-label="image.label"
            :aria-pressed="active === i"
            @click="show(i)"
          ></button>
        </div>
        <span>0{{ active + 1 }} / 0{{ images.length }}</span>
      </div>
      <span class="sr-only" aria-live="polite">
        {{ images[active]?.label }}
      </span>
    </div>
    <RouterLink
      class="product-text product-info-link"
      :to="`/products/${product.id}`"
    >
      <span>
        {{ product.familyName || '无香器具' }} / {{ product.categoryName }}
      </span>
      <h3>{{ product.name }}</h3>
      <p>{{ product.spec }} / {{ product.unit }}</p>
      <p class="scent-intro">{{ product.subtitle }}</p>
    </RouterLink>
    <div class="card-purchase">
      <strong>{{ money(product.priceCents) }}</strong>
      <button
        :aria-label="`将${product.name}加入购物袋`"
        :disabled="busy || product.stock < 1"
        @click="add"
      >
        {{ busy ? '正在加入…' : product.stock < 1 ? '暂时售罄' : '加入购物袋' }}
      </button>
    </div>
  </article>
</template>
