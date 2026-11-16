<script setup lang="ts">
import { onScopeDispose, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import type { OrderDetail } from '@shared/models'
import { errorText } from '@shared/api'
import { useOrderTime } from '@shared/order-time'
import { api } from '../state'
import State from '../components/State.vue'
import OrderContent from '../components/OrderContent.vue'
import OrderSupport from '../components/OrderSupport.vue'
const route = useRoute(),
  data = ref<OrderDetail>(),
  loading = ref(false),
  busy = ref(false),
  error = ref('')
const isExpired = useOrderTime()
let generation = 0,
  operation = 0
onScopeDispose(() => {
  generation++
  operation++
})
async function load() {
  const current = ++generation
  loading.value = true
  error.value = ''
  try {
    const result = await api.request<OrderDetail>(`/orders/${route.params.id}`)
    if (current === generation) data.value = result
  } catch (e) {
    if (current === generation) error.value = errorText(e)
  } finally {
    if (current === generation) loading.value = false
  }
}
async function action(name: 'cancel' | 'receive') {
  if (busy.value || !data.value) return
  const id = data.value.order.id,
    current = ++operation
  if (
    !window.confirm(
      name === 'cancel'
        ? '确认申请取消？已发起付款的订单须先由平台核对关闭，再恢复库存。'
        : '确认已收到并检查这笔订单的全部商品？',
    )
  )
    return
  busy.value = true
  error.value = ''
  try {
    const result = await api.request<OrderDetail>(`/orders/${id}/${name}`, {
      method: 'POST',
    })
    if (current === operation) data.value = result
  } catch (e) {
    if (current === operation) {
      // A payment can win the cancellation race. Refresh the real order before
      // offering another action; keep the original error visible for the buyer.
      await load()
      if (current === operation) error.value = errorText(e)
    }
  } finally {
    if (current === operation) busy.value = false
  }
}
watch(
  () => route.params.id,
  () => {
    operation++
    busy.value = false
    data.value = undefined
    void load()
  },
  { immediate: true },
)
</script>
<template>
  <div class="wrap page narrow">
    <RouterLink class="text-link breadcrumb" to="/orders">
      ← 我的订单
    </RouterLink>
    <State :loading="loading" :error="error" @retry="load" />
    <template v-if="data && !loading">
      <div class="page-heading">
        <p class="eyebrow">ORDER DETAILS</p>
        <h1>
          {{ isExpired(data.order) ? '付款时间已过' : data.order.statusName }}
        </h1>
      </div>
      <OrderContent :data="data" />
      <OrderSupport
        :key="`${data.order.id}:${data.order.status}`"
        :order="data"
      />
      <div class="actions order-actions">
        <RouterLink
          v-if="data.order.status === 0"
          class="btn"
          :to="`/payment/${data.order.id}`"
        >
          {{ isExpired(data.order) ? '查看付款状态' : '继续付款' }}
        </RouterLink>
        <button
          v-if="data.order.status === 0"
          class="btn secondary"
          :disabled="busy"
          @click="action('cancel')"
        >
          取消订单
        </button>
        <button
          v-if="data.order.status === 3"
          class="btn"
          :disabled="busy"
          @click="action('receive')"
        >
          确认收货
        </button>
        <RouterLink class="text-link" to="/catalog">继续探索</RouterLink>
      </div>
    </template>
  </div>
</template>
