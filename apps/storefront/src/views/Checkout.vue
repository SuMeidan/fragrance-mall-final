<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { errorText } from '@shared/api'
import { asset, money } from '@shared/format'
import type { Cart, OrderDetail } from '@shared/models'
import { api, cartCount } from '../state'
import AddressBook from '../components/AddressBook.vue'
import State from '../components/State.vue'
import CommerceSteps from '../components/CommerceSteps.vue'
const router = useRouter(),
  data = ref<Cart>(),
  addressId = ref<number>(),
  error = ref(''),
  loading = ref(false),
  busy = ref(false)
async function load() {
  loading.value = true
  error.value = ''
  try {
    data.value = await api.request<Cart>('/cart')
  } catch (e) {
    error.value = errorText(e)
  } finally {
    loading.value = false
  }
}
async function submit() {
  if (!addressId.value || busy.value) return
  busy.value = true
  error.value = ''
  const storageKey = `scent.checkout.${api.user.value?.id}.${addressId.value}`
  let key = sessionStorage.getItem(storageKey)
  if (!key) {
    key = crypto.randomUUID()
    sessionStorage.setItem(storageKey, key)
  }
  try {
    const result = await api.request<OrderDetail>('/orders', {
      method: 'POST',
      headers: { 'Idempotency-Key': key },
      body: JSON.stringify({ addressId: addressId.value }),
    })
    sessionStorage.removeItem(storageKey)
    cartCount.value = 0
    await router.replace(`/payment/${result.order.id}`)
  } catch (e) {
    error.value = errorText(e)
  } finally {
    busy.value = false
  }
}
onMounted(load)
</script>
<template>
  <div class="wrap page">
    <CommerceSteps :active="1" />
    <RouterLink class="text-link breadcrumb" to="/cart">
      ← 返回购物袋
    </RouterLink>
    <div class="page-heading">
      <p class="eyebrow">ONE STEP CLOSER</p>
      <h1>确认你的选择</h1>
      <p>创建订单后，再前往付款。</p>
    </div>
    <State :loading="loading" @retry="load" />
    <p v-if="error" class="form-error" role="alert">
      {{ error }}
      <button class="text-link" @click="load">刷新清单</button>
    </p>
    <div v-if="data?.items.length" class="commerce-layout">
      <div>
        <AddressBook v-model="addressId" selectable />
        <section class="checkout-items">
          <h2>商品清单</h2>
          <article v-for="item in data.items" :key="item.productId">
            <img
              class="checkout-item-photo"
              :src="asset(item.imagePath)"
              :alt="item.name"
            />
            <div>
              <h3>{{ item.name }}</h3>
              <p>{{ item.spec }} · {{ item.quantity }}{{ item.unit }}</p>
              <p v-if="!item.available" class="form-error">
                当前不可结算，请返回购物袋调整。
              </p>
            </div>
            <span>{{ money(item.subtotalCents) }}</span>
          </article>
        </section>
      </div>
      <aside class="order-summary">
        <p class="eyebrow">ORDER SUMMARY</p>
        <h2>确认订单</h2>
        <dl>
          <div>
            <dt>商品金额</dt>
            <dd>{{ money(data.totalCents) }}</dd>
          </div>
          <div>
            <dt>运费</dt>
            <dd>由 SCENT 承担</dd>
          </div>
          <div class="total">
            <dt>应付</dt>
            <dd>{{ money(data.totalCents) }}</dd>
          </div>
        </dl>
        <p class="quiet">
          最终价格与库存将在提交时核对。网络中断后，可重试同一笔提交。
        </p>
        <button
          class="btn full"
          :disabled="busy || !addressId || !data.canCheckout"
          @click="submit"
        >
          {{ busy ? '正在创建订单…' : '确认下单' }}
        </button>
        <RouterLink class="text-link" to="/orders">查看已有订单</RouterLink>
      </aside>
    </div>
    <State v-else-if="!loading && !error" empty="购物袋为空。">
      <RouterLink class="text-link" to="/catalog">继续选购</RouterLink>
    </State>
  </div>
</template>
