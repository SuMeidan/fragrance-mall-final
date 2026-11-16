<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { errorText } from '@shared/api'
import { asset, dateTime, money } from '@shared/format'
import type { Order, OrderDetail, Page } from '@shared/models'
import { api, cartCount, fail } from '../state'
import AccountNav from '../components/AccountNav.vue'
import State from '../components/State.vue'
import heroImage from '../assets/refined/hero.webp'
import { publicProducts } from '../public-catalog'
const router = useRouter()
const recent = ref<OrderDetail[]>([]),
  loading = ref(true),
  error = ref(''),
  greetingImage = ref(heroImage)
const request = new AbortController()
async function loadRecent() {
  loading.value = true
  error.value = ''
  try {
    const page = await api.request<Page<Order>>('/orders?pageSize=2', {
      signal: request.signal,
    })
    const details = await Promise.all(
      page.items.slice(0, 2).map((o) =>
        api.request<OrderDetail>(`/orders/${o.id}`, {
          signal: request.signal,
        }),
      ),
    )
    if (!request.signal.aborted) recent.value = details
  } catch (e) {
    if (!request.signal.aborted) error.value = errorText(e)
  } finally {
    if (!request.signal.aborted) loading.value = false
  }
}
onMounted(() => {
  void loadRecent()
  void publicProducts({ categoryId: 4 })
    .then((products) => {
      const image = (products.find((p) => p.name === '月白陶石') || products[0])
        ?.imagePath
      if (!request.signal.aborted && image) greetingImage.value = asset(image)
    })
    .catch(() => {
      /* The existing brand photograph remains a valid fallback. */
    })
})
onUnmounted(() => request.abort())
async function logout() {
  try {
    await api.logout()
  } catch (e) {
    fail(e)
  } finally {
    cartCount.value = 0
    router.replace('/')
  }
}
</script>
<template>
  <div class="wrap page narrow">
    <div class="page-heading account-heading">
      <div>
        <p class="eyebrow">YOUR ACCOUNT</p>
        <h1>你好，{{ api.user.value?.displayName }}</h1>
        <p>{{ api.user.value?.loginName }}</p>
      </div>
      <button class="text-link" @click="logout">退出登录</button>
    </div>
    <div class="account-layout">
      <AccountNav />
      <div>
        <section class="account-greeting">
          <img :src="greetingImage" alt="SCENT香氛器物" />
          <div>
            <p class="eyebrow">A PLACE FOR YOUR EVERYDAY</p>
            <h2>
              你的香氛日常，
              <br />
              从这里继续。
            </h2>
            <RouterLink class="text-link" to="/orders">
              查看我的订单 ↗
            </RouterLink>
          </div>
        </section>
        <section class="account-recent" aria-labelledby="recent-title">
          <div class="account-section-heading">
            <h2 id="recent-title">最近的订单</h2>
            <RouterLink class="text-link" to="/orders">查看全部 ↗</RouterLink>
          </div>
          <State
            :loading="loading"
            :error="error"
            :empty="
              !loading && !error && !recent.length
                ? '还没有订单，去发现一款喜欢的作品。'
                : ''
            "
            @retry="loadRecent"
          />
          <div v-if="!loading && !error" class="recent-orders">
            <RouterLink
              v-for="entry in recent"
              :key="entry.order.id"
              :to="`/orders/${entry.order.id}`"
              class="recent-order"
            >
              <div class="recent-order-head">
                <span>
                  {{ dateTime(entry.order.createdAt) }} ·
                  {{ entry.order.orderNo }}
                </span>
                <span class="recent-order-status">
                  {{ entry.order.statusName }}
                </span>
              </div>
              <div
                v-for="item in entry.items.slice(0, 2)"
                :key="item.id"
                class="recent-order-item"
              >
                <img
                  :src="asset(item.imagePath)"
                  :alt="item.name"
                  loading="lazy"
                />
                <div>
                  <h3>{{ item.name }}</h3>
                  <p>{{ item.spec }} · {{ item.quantity }}{{ item.unit }}</p>
                </div>
                <span>{{ money(item.subtotalCents) }}</span>
              </div>
              <div class="recent-order-total">
                <span>{{ entry.order.itemCount }} 件商品</span>
                <span>合计 {{ money(entry.order.totalCents) }}　↗</span>
              </div>
            </RouterLink>
          </div>
        </section>
      </div>
    </div>
  </div>
</template>
