<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import type { Capabilities, OrderDetail, Settlement } from '@shared/models'
import { errorText, HttpError } from '@shared/api'
import { useOrderTime } from '@shared/order-time'
import { asset, addressText, money, dateTime } from '@shared/format'
import { api } from '../state'
import State from '../components/State.vue'
import CommerceSteps from '../components/CommerceSteps.vue'
const route = useRoute(),
  data = ref<OrderDetail>(),
  funds = ref<Settlement>(),
  capabilities = ref<Capabilities>(),
  busy = ref(false),
  loading = ref(false),
  error = ref(''),
  uncertain = ref(false)
const isExpired = useOrderTime()
const elapsed = computed(() => isExpired(data.value?.order))
const confirmed = computed(
  () =>
    !!data.value &&
    [1, 2, 3, 4].includes(data.value.order.status) &&
    funds.value?.payment?.status === 'SUCCESS' &&
    !funds.value.payment.needsReview &&
    (!funds.value.afterSale || funds.value.afterSale.state === 'REJECTED') &&
    funds.value?.refund?.status !== 'SUCCESS',
)
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
  data.value = undefined
  try {
    const [order, settlement, caps] = await Promise.all([
      api.request<OrderDetail>(`/orders/${route.params.id}`),
      api.request<Settlement>(`/orders/${route.params.id}/settlement`),
      api.request<Capabilities>('/capabilities', {}, false),
    ])
    if (current === generation) {
      data.value = order
      funds.value = settlement
      capabilities.value = caps
    }
  } catch (e) {
    if (current === generation) error.value = errorText(e)
  } finally {
    if (current === generation) loading.value = false
  }
}
async function synchronize() {
  if (busy.value || !data.value) return
  const current = ++operation,
    id = data.value.order.id
  busy.value = true
  error.value = ''
  try {
    await api.request(`/orders/${id}/payment/sync`, {
      method: 'POST',
    })
    if (current === operation) {
      uncertain.value = false
      await load()
    }
  } catch (e) {
    if (current === operation) {
      uncertain.value = true
      error.value = errorText(e)
    }
  } finally {
    if (current === operation) busy.value = false
  }
}
async function pay() {
  if (busy.value || elapsed.value || uncertain.value || !data.value) return
  const current = ++operation,
    id = data.value.order.id
  busy.value = true
  error.value = ''
  try {
    const result = await api.request<{ url: string }>(`/orders/${id}/payment`, {
      method: 'POST',
    })
    if (current !== operation) return
    if (elapsed.value) {
      busy.value = false
      return
    }
    const url = new URL(result.url)
    if (
      url.origin !== 'https://openapi-sandbox.dl.alipaydev.com' ||
      url.pathname !== '/gateway.do'
    )
      throw new Error('付款地址无法确认，请保留订单并联系店铺。')
    window.location.assign(url.href)
  } catch (e) {
    if (current === operation) {
      uncertain.value =
        e instanceof HttpError &&
        ['NETWORK_ERROR', 'PAYMENT_CHECK_PENDING'].includes(e.code)
      error.value = errorText(e)
      busy.value = false
    }
  }
}
watch(
  () => route.params.id,
  async () => {
    const current = ++operation
    busy.value = false
    uncertain.value = false
    await load()
    if (
      current === operation &&
      route.query.returned &&
      capabilities.value?.paymentConfigured &&
      funds.value?.payment &&
      data.value?.order.status === 0
    )
      await synchronize()
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
      <CommerceSteps :active="confirmed ? 3 : 2" />
      <section
        v-if="confirmed"
        class="payment-success-layout"
        aria-label="付款成功"
      >
        <img
          v-if="data.items[0]"
          class="payment-success-photo"
          :src="asset(data.items[0].imagePath)"
          :alt="data.items[0].name"
        />
        <div>
          <span class="payment-success-mark" aria-hidden="true">✓</span>
          <p class="eyebrow">THANK YOU</p>
          <h1>喜欢，即将抵达。</h1>
          <p>付款成功，我们会认真准备你的每一件作品。</p>
          <dl>
            <div>
              <dt>订单编号</dt>
              <dd>{{ data.order.orderNo }}</dd>
            </div>
            <div>
              <dt>付款金额</dt>
              <dd>
                {{
                  money(funds?.payment?.amountCents || data.order.totalCents)
                }}
              </dd>
            </div>
            <div>
              <dt>配送进度</dt>
              <dd>{{ data.order.statusName }}</dd>
            </div>
          </dl>
          <div class="actions">
            <RouterLink class="btn" :to="`/orders/${data.order.id}`">
              查看订单详情 ↗
            </RouterLink>
            <RouterLink class="btn secondary" to="/catalog">
              继续探索
            </RouterLink>
          </div>
          <p class="quiet">收货信息和后续进度，可在「我的订单」中查看。</p>
        </div>
      </section>
      <section v-else class="payment-layout">
        <div class="payment-sheet">
          <p class="eyebrow">
            {{ data.order.status === 0 ? 'AWAITING PAYMENT' : 'ORDER STATUS' }}
          </p>
          <h1>
            {{
              data.order.status === 0
                ? elapsed
                  ? '付款时间已过'
                  : uncertain
                    ? '付款结果待核对'
                    : '离喜欢，更近一步。'
                : data.order.statusName
            }}
          </h1>
          <p>订单 {{ data.order.orderNo }}</p>
          <p class="payment-amount">{{ money(data.order.totalCents) }}</p>
          <template v-if="data.order.status === 0">
            <p v-if="elapsed" class="inline-note" role="status">
              付款期限为
              {{
                dateTime(data.order.expiresAt)
              }}。请先核对付款结果；订单关闭后可重新下单。
              已发起付款的订单，库存会在服务端确认关闭后恢复。
            </p>
            <p v-else>请在 {{ dateTime(data.order.expiresAt) }} 前完成付款。</p>
            <div v-if="!capabilities?.paymentConfigured" class="inline-note">
              支付暂未开放。你的订单已保存，可在订单页查看或取消。
            </div>
            <template v-else>
              <p class="quiet">支付宝沙箱 · 使用沙箱账号，不产生真实资金交易</p>
              <p class="quiet">
                扫码需使用支付宝沙箱客户端。正式版支付宝无法支付沙箱二维码。
              </p>
              <div
                v-if="funds?.payment?.status === 'CLOSED'"
                class="inline-note"
              >
                平台付款已关闭，请到订单页取消本单，再重新选择。
              </div>
              <button
                v-else-if="!elapsed && !uncertain"
                class="btn"
                :disabled="busy || !!error"
                @click="pay"
              >
                {{ busy ? '正在核对…' : '前往支付宝沙箱' }}
              </button>
              <p v-if="uncertain" class="inline-note" role="status">
                平台暂未确认结果，请使用原订单核对。核对完成前，保留当前订单与库存。
              </p>
              <button
                class="btn secondary"
                :disabled="busy"
                @click="synchronize"
              >
                核对付款结果
              </button>
              <p class="quiet">
                付款返回后由店铺核对平台结果。结果不明时请先核对，无需重复付款。
              </p>
            </template>
            <button
              v-if="!capabilities?.paymentConfigured"
              class="btn secondary"
              :disabled="loading || busy"
              @click="load"
            >
              刷新订单状态
            </button>
          </template>
          <div v-if="funds?.payment?.needsReview" class="inline-note">
            平台已确认收款，店铺正在核对关闭订单的退款。请查看售后进度。
          </div>
          <div v-if="funds?.refund?.status === 'SUCCESS'" class="inline-note">
            整单已退款 {{ money(funds.refund.amountCents) }} ·
            {{ dateTime(funds.refund.successAt) }}
          </div>
          <RouterLink class="text-link" :to="`/orders/${data.order.id}`">
            查看订单详情
          </RouterLink>
          <RouterLink class="text-link" to="/catalog">继续探索</RouterLink>
        </div>
        <aside class="payment-receipt">
          <p class="eyebrow">YOUR SELECTION</p>
          <article v-for="item in data.items" :key="item.id">
            <img :src="asset(item.imagePath)" :alt="item.name" />
            <div>
              <h2>{{ item.name }}</h2>
              <p>{{ item.spec }} / {{ item.quantity }}{{ item.unit }}</p>
            </div>
            <span>{{ money(item.priceCents * item.quantity) }}</span>
          </article>
          <p class="quiet">配送由 SCENT 承担</p>
          <p>{{ addressText(data.order) }}</p>
        </aside>
      </section>
    </template>
  </div>
</template>
