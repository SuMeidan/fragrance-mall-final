<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import Header from './components/Header.vue'
import Advisor from './components/Advisor.vue'
import { api, notice, syncCart } from './state'
const route = useRoute(),
  home = computed(() => route.path === '/')
onMounted(async () => {
  if (api.token.value) {
    try {
      await api.me()
      await syncCart()
    } catch {
      /* Page actions show recoverable errors. */
    }
  }
})
</script>

<template>
  <div
    :id="home ? 'home-view' : 'storefront-view'"
    :class="['refined-store', { 'is-home': home }]"
  >
    <a class="skip" href="#main">跳至主要内容</a>
    <Header />
    <main id="main" tabindex="-1"><RouterView /></main>
    <footer v-if="!home" class="site-footer">
      <RouterLink class="wordmark" to="/">SCENT</RouterLink>
      <p>让无形，成为风格。</p>
      <span>LET SCENT SPEAK.</span>
      <RouterLink to="/catalog">探索所有作品</RouterLink>
      <small>课程设计 · 虚构品牌与商品</small>
    </footer>
  </div>
  <Advisor />
  <div v-if="notice" class="toast" role="status">
    {{ notice }}
    <button aria-label="关闭提示" @click="notice = ''">×</button>
  </div>
</template>
