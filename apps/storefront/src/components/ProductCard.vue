<script setup lang="ts">
import { ref } from 'vue'
import type { Product } from '@shared/models'
import { asset, money } from '@shared/format'
import { imageSource, imageSet } from '../catalog-display'
const props = defineProps<{ product: Product; index?: number }>()
const flipped = ref(false)
let touch: { x: number; y: number; id: number } | undefined,
  suppressUntil = 0
function touchStart(event: PointerEvent) {
  if (event.pointerType !== 'mouse')
    touch = { x: event.clientX, y: event.clientY, id: event.pointerId }
}
function touchEnd(event: PointerEvent) {
  if (!touch || touch.id !== event.pointerId) return
  const dx = event.clientX - touch.x,
    dy = event.clientY - touch.y
  touch = undefined
  if (
    props.product.scentImagePath &&
    Math.abs(dx) > 45 &&
    Math.abs(dx) > Math.abs(dy) * 1.4
  ) {
    flipped.value = !flipped.value
    suppressUntil = Date.now() + 500
  }
}
function imageClick(event: MouseEvent) {
  if (Date.now() < suppressUntil) {
    event.preventDefault()
    event.stopPropagation()
  }
}
</script>
<template>
  <article class="product-card">
    <RouterLink
      class="product-card-link"
      :to="`/products/${product.id}`"
      :aria-describedby="
        product.scentImagePath ? `product-images-${product.id}` : undefined
      "
      @keydown.left.prevent="
        flipped = product.scentImagePath ? !flipped : false
      "
      @keydown.right.prevent="
        flipped = product.scentImagePath ? !flipped : false
      "
      @click.capture="imageClick"
    >
      <div
        class="product-image"
        :class="{ flipped }"
        @pointerenter="
          (event) => {
            if (event.pointerType === 'mouse')
              flipped = !!product.scentImagePath
          }
        "
        @pointerleave="
          (event) => {
            if (event.pointerType === 'mouse') flipped = false
          }
        "
        @pointerdown="touchStart"
        @pointerup="touchEnd"
        @pointercancel="touch = undefined"
      >
        <img
          :src="asset(imageSource(product.imagePath, 480))"
          :srcset="imageSet(product.imagePath)"
          sizes="(max-width: 760px) calc((100vw - 54px) / 2), (max-width: 1100px) 30vw, 23vw"
          :alt="product.name"
          loading="lazy"
          decoding="async"
        />
        <img
          v-if="product.scentImagePath"
          class="scent-image"
          :src="asset(imageSource(product.scentImagePath, 480))"
          :srcset="imageSet(product.scentImagePath)"
          sizes="(max-width: 700px) 86vw, 30vw"
          alt="香调意象（非配方或成分图）"
          loading="lazy"
        />
        <span
          v-if="product.imagePath.includes('/types/')"
          class="image-caption"
        >
          形态示意
        </span>
        <span class="image-index">
          {{ String((index || 0) + 1).padStart(2, '0') }}
        </span>
      </div>
      <div class="product-caption">
        <div>
          <h3>{{ product.name }}</h3>
          <p>{{ product.spec }} · {{ product.categoryName }}</p>
          <p class="eyebrow sr-only">
            {{ product.familyName || '无香器具' }} / {{ product.categoryName }}
          </p>
        </div>
        <span>{{ money(product.priceCents) }}</span>
      </div>
    </RouterLink>
    <span
      v-if="product.scentImagePath"
      :id="`product-images-${product.id}`"
      class="sr-only"
    >
      悬停、左右滑动或使用左右方向键切换图片。
    </span>
  </article>
</template>
