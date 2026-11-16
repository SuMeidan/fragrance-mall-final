<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { asset, money } from '@shared/format'
import { errorText } from '@shared/api'
import type { Cart } from '@shared/models'
import { api, cartCount } from '../state'
import State from '../components/State.vue'
import CommerceSteps from '../components/CommerceSteps.vue'
const data = ref<Cart>(),
  error = ref(''),
  loading = ref(false),
  busy = ref(false)
async function load() {
  loading.value = true
  error.value = ''
  try {
    data.value = await api.request<Cart>('/cart')
    cartCount.value = data.value.items.length
  } catch (e) {
    error.value = errorText(e)
  } finally {
    loading.value = false
  }
}
async function change(id: number, qty?: number) {
  busy.value = true
  error.value = ''
  try {
    await api.request(`/cart/${id}`, {
      method: qty == null ? 'DELETE' : 'PATCH',
      body: qty == null ? undefined : JSON.stringify({ quantity: qty }),
    })
    await load()
  } catch (e) {
    const message = errorText(e)
    await load()
    error.value = message
  } finally {
    busy.value = false
  }
}
onMounted(load)
</script>
<template>
  <div class="wrap page">
    <CommerceSteps :active="0" />
    <div class="page-heading">
      <p class="eyebrow">YOUR SELECTION</p>
      <h1>购物袋</h1>
      <p>留住喜欢的气味。</p>
    </div>
    <State
      :loading="loading"
      :error="error"
      :empty="
        !loading && !error && !data?.items.length
          ? '购物袋还是空的，去发现喜欢的作品。'
          : ''
      "
      @retry="load"
    >
      <RouterLink
        v-if="!loading && !data?.items.length"
        class="text-link"
        to="/catalog"
      >
        探索所有作品
      </RouterLink>
    </State>
    <div v-if="data?.items.length" class="commerce-layout">
      <div class="cart-lines">
        <article
          v-for="item in data.items"
          :key="item.productId"
          class="cart-line"
        >
          <RouterLink :to="`/products/${item.productId}`">
            <img :src="asset(item.imagePath)" :alt="item.name" />
          </RouterLink>
          <div class="cart-line-copy">
            <RouterLink :to="`/products/${item.productId}`">
              <h2>{{ item.name }}</h2>
            </RouterLink>
            <p>{{ item.spec }}</p>
            <span>{{ money(item.priceCents) }}</span>
            <p v-if="!item.available" class="form-error">
              商品已失效或库存不足，请移除或调整数量。
            </p>
            <div class="quantity">
              <button
                :disabled="busy || item.quantity <= 1"
                :aria-label="`减少${item.name}数量`"
                @click="change(item.productId, item.quantity - 1)"
              >
                −
              </button>
              <span>{{ item.quantity }}</span>
              <button
                :disabled="
                  busy || item.quantity >= 5 || item.quantity >= item.stock
                "
                :aria-label="`增加${item.name}数量`"
                @click="change(item.productId, item.quantity + 1)"
              >
                ＋
              </button>
            </div>
          </div>
          <div class="line-end">
            <strong>{{ money(item.subtotalCents) }}</strong>
            <button
              class="text-link"
              :disabled="busy"
              :aria-label="`移除${item.name}`"
              @click="change(item.productId)"
            >
              移除
            </button>
          </div>
        </article>
      </div>
      <aside class="order-summary">
        <p class="eyebrow">ORDER SUMMARY</p>
        <h2>你的选择</h2>
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
            <dt>合计</dt>
            <dd>{{ money(data.totalCents) }}</dd>
          </div>
        </dl>
        <RouterLink
          v-if="data.canCheckout && !busy"
          class="btn full"
          to="/checkout"
        >
          前往结算
        </RouterLink>
        <button v-else class="btn full" disabled>请先处理失效商品</button>
        <RouterLink class="text-link" to="/catalog">继续探索</RouterLink>
      </aside>
    </div>
  </div>
</template>
