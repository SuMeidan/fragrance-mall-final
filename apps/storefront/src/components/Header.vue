<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ShoppingBag, UserRound, Search, ChevronDown, X } from '@lucide/vue'
import type { CategoryTree, Cart } from '@shared/models'
import { api, cartCount, catalog } from '../state'
import { asset, money } from '@shared/format'
import { errorText } from '@shared/api'
const route = useRoute(),
  router = useRouter(),
  open = ref(false),
  scrolled = ref(false),
  categories = ref<CategoryTree[]>([]),
  header = ref<HTMLElement>(),
  toggle = ref<HTMLButtonElement>(),
  bag = ref<HTMLDialogElement>(),
  preview = ref<Cart>(),
  bagLoading = ref(false),
  bagError = ref('')
let generation = 0
async function search() {
  close()
  if (route.path !== '/catalog') await router.push('/catalog')
  await nextTick()
  window.scrollTo({ top: 0, behavior: 'instant' })
  document
    .querySelector<HTMLInputElement>('#catalog-search')
    ?.focus({ preventScroll: true })
}
function scroll() {
  scrolled.value = scrollY > innerHeight * 0.45
}
function close(focus = false) {
  open.value = false
  if (focus) toggle.value?.focus()
}
function outside(event: Event) {
  if (!header.value?.contains(event.target as Node)) close()
}
function escape(event: KeyboardEvent) {
  if (event.key === 'Escape' && open.value) {
    event.preventDefault()
    close(true)
  }
}
async function openBag() {
  const version = ++generation,
    token = api.token.value
  if (!token) return
  bag.value?.showModal()
  preview.value = undefined
  bagLoading.value = true
  bagError.value = ''
  try {
    const result = await api.request<Cart>('/cart')
    if (version === generation && token === api.token.value) {
      preview.value = result
      cartCount.value = result.items.length
    }
  } catch (error) {
    if (version === generation) bagError.value = errorText(error)
  } finally {
    if (version === generation) bagLoading.value = false
  }
}
watch(
  () => route.fullPath,
  () => {
    close()
    generation++
    bag.value?.close()
    scroll()
  },
)
watch(api.token, () => {
  generation++
  preview.value = undefined
  bag.value?.close()
})
onMounted(async () => {
  scroll()
  window.addEventListener('scroll', scroll, { passive: true })
  document.addEventListener('pointerdown', outside)
  document.addEventListener('keydown', escape)
  try {
    categories.value = (await catalog()).categories
  } catch {
    /* Catalog page offers retry. */
  }
})
onUnmounted(() => {
  window.removeEventListener('scroll', scroll)
  document.removeEventListener('pointerdown', outside)
  document.removeEventListener('keydown', escape)
})
</script>
<template>
  <header
    ref="header"
    class="site-header"
    :class="{
      solid: route.path !== '/' || scrolled,
      expanded: open,
      'exhibition-nav':
        route.path === '/' ||
        route.path === '/catalog' ||
        route.path.startsWith('/products/'),
    }"
    @focusout="
      (e) => {
        if (e.relatedTarget && !header?.contains(e.relatedTarget as Node))
          close()
      }
    "
  >
    <RouterLink class="wordmark" to="/" aria-label="SCENT 首页">
      SCENT
    </RouterLink>
    <nav class="desktop-nav" aria-label="主导航">
      <RouterLink to="/">首页</RouterLink>
      <button
        ref="toggle"
        class="category-toggle"
        :aria-expanded="open"
        aria-controls="category-menu"
        @click="open = !open"
        @keydown.down.prevent="open = true"
      >
        商品
        <ChevronDown :size="13" />
      </button>
    </nav>
    <div class="header-actions">
      <button class="header-search" aria-label="打开商品搜索" @click="search">
        <Search :size="20" aria-hidden="true" />
      </button>
      <RouterLink
        :to="api.token.value ? '/account' : '/login'"
        aria-label="我的账户"
      >
        <UserRound :size="20" />
      </RouterLink>
      <RouterLink
        to="/cart"
        class="bag"
        aria-label="购物袋"
        aria-describedby="header-bag-count"
      >
        <ShoppingBag :size="20" />
        <span v-if="cartCount" aria-hidden="true">{{ cartCount }}</span>
      </RouterLink>
      <span id="header-bag-count" class="sr-only">
        购物袋内有 {{ cartCount }} 款商品
      </span>
    </div>
    <section
      v-if="open"
      id="category-menu"
      class="category-menu"
      aria-label="商品分类"
    >
      <div class="category-menu-head">
        <RouterLink to="/catalog">
          全部商品
          <small>探索完整香氛与器物系列</small>
        </RouterLink>
        <button aria-label="关闭商品分类" @click="close(true)">
          <X :size="19" />
        </button>
      </div>
      <div class="category-columns">
        <div v-for="c in categories" :key="c.id">
          <RouterLink :to="`/catalog?categoryId=${c.id}`">
            {{ c.name }}
          </RouterLink>
          <ul>
            <li v-for="leaf in c.children" :key="leaf.id">
              <RouterLink :to="`/catalog?categoryId=${leaf.id}`">
                {{ leaf.name }}
              </RouterLink>
            </li>
          </ul>
        </div>
      </div>
    </section>
  </header>
  <!-- Retain an accessible preview for callers; the single header bag goes to the full cart. -->
  <dialog
    ref="bag"
    class="member-dialog production-bag"
    aria-labelledby="bag-title"
  >
    <button aria-label="关闭购物袋预览" @click="bag?.close()">关闭 ×</button>
    <h2 id="bag-title">购物袋</h2>
    <p v-if="bagLoading" role="status">正在读取购物袋…</p>
    <p v-if="bagError" role="alert">
      {{ bagError }}
      <button @click="openBag">重试</button>
    </p>
    <template v-if="preview">
      <p v-if="!preview.items.length">购物袋还是空的</p>
      <div
        v-for="item in preview.items"
        :key="item.productId"
        class="bag-preview-row"
      >
        <img :src="asset(item.imagePath)" :alt="item.name" />
        <RouterLink :to="`/products/${item.productId}`">
          {{ item.name }}
        </RouterLink>
        <span>{{ money(item.subtotalCents) }}</span>
      </div>
    </template>
    <RouterLink class="btn" to="/cart">查看并编辑购物袋</RouterLink>
  </dialog>
</template>
