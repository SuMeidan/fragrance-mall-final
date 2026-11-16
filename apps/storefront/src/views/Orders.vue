<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { errorText, query } from '@shared/api'
import { dateTime, money } from '@shared/format'
import { useOrderTime } from '@shared/order-time'
import type { Order, Page } from '@shared/models'
import { api } from '../state'
import State from '../components/State.vue'
import AccountNav from '../components/AccountNav.vue'
const route = useRoute(),
  router = useRouter(),
  data = ref<Page<Order>>(),
  loading = ref(false),
  error = ref('')
const isExpired = useOrderTime()
const statuses = [
  ['', '全部'],
  ['0', '待付款'],
  ['1', '已支付'],
  ['2', '配货中'],
  ['3', '已发货'],
  ['4', '已完成'],
]
let generation = 0
async function load() {
  const current = ++generation
  loading.value = true
  error.value = ''
  try {
    const result = await api.request<Page<Order>>(
      '/orders' +
        query({
          status: String(route.query.status || ''),
          page: Number(route.query.page) || 1,
        }),
    )
    if (current === generation) data.value = result
  } catch (e) {
    if (current === generation) error.value = errorText(e)
  } finally {
    if (current === generation) loading.value = false
  }
}
function filter(status: string) {
  router.push({ query: { status: status || undefined } })
}
watch(() => route.fullPath, load, { immediate: true })
</script>
<template>
  <div class="wrap page narrow">
    <div class="page-heading">
      <p class="eyebrow">YOUR ORDERS</p>
      <h1>每一份期待</h1>
      <p>订单、物流与收货进度。</p>
    </div>
    <div class="account-layout">
      <AccountNav />
      <div>
        <nav class="category-tabs" aria-label="订单状态">
          <button
            v-for="[value, label] in statuses"
            :key="value"
            :class="{ active: String(route.query.status || '') === value }"
            @click="filter(value!)"
          >
            {{ label }}
          </button>
        </nav>
        <State
          :loading="loading"
          :error="error"
          :empty="
            !loading && !error && !data?.items.length ? '这里还没有订单。' : ''
          "
          @retry="load"
        />
        <div v-if="!loading" class="order-list">
          <RouterLink
            v-for="order in data?.items"
            :key="order.id"
            :to="`/orders/${order.id}`"
          >
            <div>
              <span class="eyebrow">{{ dateTime(order.createdAt) }}</span>
              <h2>
                {{ isExpired(order) ? '付款时间已过' : order.statusName }}
              </h2>
              <p class="quiet">订单 {{ order.orderNo }}</p>
              <p>{{ order.itemCount }} 件商品</p>
            </div>
            <div>
              <strong>{{ money(order.totalCents) }}</strong>
              <span class="text-link">查看订单</span>
            </div>
          </RouterLink>
        </div>
        <nav
          v-if="data && data.total > data.pageSize"
          class="pagination"
          aria-label="订单分页"
        >
          <button
            :disabled="data.page <= 1 || loading"
            @click="
              router.push({ query: { ...route.query, page: data.page - 1 } })
            "
          >
            ← 上一页
          </button>
          <span>{{ data.page }}</span>
          <button
            :disabled="data.page * data.pageSize >= data.total || loading"
            @click="
              router.push({ query: { ...route.query, page: data.page + 1 } })
            "
          >
            下一页 →
          </button>
        </nav>
      </div>
    </div>
  </div>
</template>
