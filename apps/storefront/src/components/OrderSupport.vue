<script setup lang="ts">
import { computed, onScopeDispose, reactive, ref, watch } from 'vue'
import { errorText } from '@shared/api'
import { dateTime, money } from '@shared/format'
import {
  saleNames,
  type AfterSale,
  type OrderDetail,
  type Settlement,
} from '@shared/models'
import { api } from '../state'
const props = defineProps<{ order: OrderDetail }>()
const data = ref<Settlement>(),
  loading = ref(false),
  busy = ref(false),
  error = ref(''),
  applying = ref(false),
  reason = ref(''),
  editingReturn = ref(false),
  feedback = ref(''),
  reviewing = ref<number>()
const shipment = reactive({ carrier: '', trackingNo: '' }),
  review = reactive({ stars: 5, content: '' })
const sale = computed(() => data.value?.afterSale)
const returnRecorded = computed(
  () => !!sale.value?.returnCarrier && !!sale.value?.returnTrackingNo,
)
const awaitingReturn = computed(
  () => sale.value?.state === 'RETURN_REQUIRED' && !sale.value.receivedAt,
)
const saleTitle = computed(() =>
  awaitingReturn.value && returnRecorded.value
    ? '待店铺确认收货'
    : sale.value
      ? saleNames[sale.value.state]
      : '',
)
const canReview = computed(
  () =>
    props.order.order.status === 4 &&
    data.value?.payment?.status === 'SUCCESS' &&
    (!sale.value || sale.value.state === 'REJECTED'),
)
const unreviewed = computed(() =>
  props.order.items.filter(
    (i) => !data.value?.reviews.some((r) => r.orderItemId === i.id),
  ),
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
    const result = await api.request<Settlement>(
      `/orders/${props.order.order.id}/settlement`,
    )
    if (current === generation) {
      data.value = result
      if (!awaitingReturn.value) editingReturn.value = false
    }
  } catch (e) {
    if (current === generation) error.value = errorText(e)
  } finally {
    if (current === generation) loading.value = false
  }
}
function editReturn() {
  Object.assign(shipment, {
    carrier: sale.value?.returnCarrier || '',
    trackingNo: sale.value?.returnTrackingNo || '',
  })
  error.value = ''
  feedback.value = ''
  editingReturn.value = true
}
function cancelReturn() {
  editingReturn.value = false
  error.value = ''
}
async function saveReturn() {
  if (busy.value || !sale.value || !data.value) return
  const current = ++operation
  const id = sale.value.id
  busy.value = true
  error.value = ''
  feedback.value = ''
  try {
    const result = await api.request<AfterSale>(`/after-sales/${id}/return`, {
      method: 'POST',
      body: JSON.stringify({
        carrier: shipment.carrier.trim(),
        trackingNo: shipment.trackingNo.trim(),
      }),
    })
    if (current !== operation || data.value?.afterSale?.id !== id) return
    data.value.afterSale = result
    editingReturn.value = false
    feedback.value = '退回物流已保存，等待店铺确认收货。'
  } catch (e) {
    if (current === operation) error.value = errorText(e)
  } finally {
    if (current === operation) busy.value = false
  }
}
async function send(path: string, body: object) {
  if (busy.value) return
  const current = ++operation
  busy.value = true
  error.value = ''
  try {
    await api.request(path, { method: 'POST', body: JSON.stringify(body) })
    if (current !== operation) return
    applying.value = false
    reviewing.value = undefined
    reason.value = ''
    review.content = ''
    editingReturn.value = false
    feedback.value = ''
    await load()
  } catch (e) {
    if (current === operation) error.value = errorText(e)
  } finally {
    if (current === operation) busy.value = false
  }
}
function startReview(id: number) {
  reviewing.value = id
  review.stars = 5
  review.content = ''
}
watch(
  () => props.order.order.id,
  () => {
    operation++
    busy.value = false
    applying.value = false
    reviewing.value = undefined
    reason.value = ''
    review.content = ''
    Object.assign(shipment, { carrier: '', trackingNo: '' })
    editingReturn.value = false
    feedback.value = ''
    void load()
  },
  { immediate: true },
)
</script>
<template>
  <section class="order-support">
    <div class="section-head compact">
      <h2>服务与体验</h2>
      <button class="text-link" :disabled="loading || busy" @click="load">
        刷新进度
      </button>
    </div>
    <p v-if="loading" class="quiet" role="status">正在读取服务进度…</p>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <template v-if="data">
      <div v-if="data.payment?.needsReview" class="inline-note">
        订单关闭后平台确认收到付款，店铺正在核对整单退款。商品不会再次发出。
      </div>
      <p v-if="data.payment?.status === 'SUCCESS'" class="quiet">
        付款已确认 {{ money(data.payment.amountCents || 0) }} ·
        {{ dateTime(data.payment.successAt) }}
      </p>
      <p
        v-if="order.order.status === 0 && data.payment?.status === 'PENDING'"
        class="inline-note"
      >
        付款尚待平台核对。请在付款状态页核对原订单；服务端确认关闭后才恢复库存。
      </p>
      <article v-if="sale" class="sale-progress">
        <h3>{{ saleTitle }}</h3>
        <p v-if="feedback" class="return-feedback" role="status">
          {{ feedback }}
        </p>
        <p class="preserve">申请原因：{{ sale.reason }}</p>
        <p v-if="sale.reviewNote" class="preserve">
          店铺回复：{{ sale.reviewNote }}
        </p>
        <p v-if="sale.returnCarrier">
          退回物流：{{ sale.returnCarrier }} · {{ sale.returnTrackingNo }}
        </p>
        <div
          v-if="awaitingReturn && returnRecorded && !editingReturn"
          class="return-confirmation"
        >
          <p>物流信息已提交。店铺确认收到退回商品后，将继续处理退款。</p>
          <button
            type="button"
            class="text-link"
            :disabled="busy || loading"
            @click="editReturn"
          >
            修改退回物流
          </button>
        </div>
        <p v-if="sale.receivedAt">
          已确认收到退回商品 · {{ dateTime(sale.receivedAt) }}
        </p>
        <p v-if="data.refund">
          退款进度：{{
            data.refund.status === 'SUCCESS'
              ? '已确认退款'
              : data.refund.status === 'FAILED'
                ? '本次未完成，待店铺重新核对'
                : '正在核对平台结果'
          }}
          <span v-if="data.refund.status === 'SUCCESS'">
            · {{ money(data.refund.amountCents) }} ·
            {{ dateTime(data.refund.successAt) }}
          </span>
        </p>
        <form
          v-if="awaitingReturn && (!returnRecorded || editingReturn)"
          class="service-form"
          @submit.prevent="saveReturn"
        >
          <p class="quiet">
            请先按店铺审核回复确认退回方式与地址，退回后填写物流。
          </p>
          <div class="two-fields">
            <label>
              承运方
              <input
                v-model="shipment.carrier"
                :disabled="busy"
                required
                maxlength="40"
                :placeholder="sale.returnCarrier || '填写退回快递'"
              />
            </label>
            <label>
              运单号
              <input
                v-model="shipment.trackingNo"
                :disabled="busy"
                required
                pattern="[A-Za-z0-9\-]{6,64}"
                minlength="6"
                maxlength="64"
                :placeholder="
                  sale.returnTrackingNo || '6—64位字母、数字或短横线'
                "
              />
            </label>
          </div>
          <div class="actions">
            <button class="btn" :disabled="busy">
              {{ busy ? '正在保存…' : '保存退回物流' }}
            </button>
            <button
              v-if="returnRecorded"
              type="button"
              class="btn secondary"
              :disabled="busy"
              @click="cancelReturn"
            >
              取消修改
            </button>
          </div>
        </form>
      </article>
      <button
        v-else-if="
          [1, 2, 3, 4].includes(order.order.status) &&
          data.payment?.status === 'SUCCESS' &&
          !applying
        "
        class="text-link"
        @click="applying = true"
      >
        申请整单售后
      </button>
      <form
        v-if="applying && !sale"
        class="service-form"
        @submit.prevent="
          send(`/orders/${order.order.id}/after-sale`, { reason })
        "
      >
        <label>
          申请原因
          <textarea
            v-model="reason"
            required
            maxlength="500"
            rows="3"
            placeholder="说明需要整单售后的原因"
          ></textarea>
        </label>
        <p class="quiet">已发货的订单需要先退回，由店铺确认收到后处理退款。</p>
        <div class="actions">
          <button class="btn" :disabled="busy">提交申请</button>
          <button
            type="button"
            class="btn secondary"
            :disabled="busy"
            @click="applying = false"
          >
            返回
          </button>
        </div>
      </form>
      <div class="own-reviews">
        <article v-for="r in data.reviews" :key="r.id">
          <h3>你的评价 · {{ r.stars }} / 5</h3>
          <p class="preserve">{{ r.content }}</p>
          <span class="quiet">{{ dateTime(r.createdAt) }}</span>
        </article>
        <template v-if="canReview">
          <div v-for="item in unreviewed" :key="item.id" class="review-choice">
            <span>{{ item.name }}</span>
            <button class="text-link" @click="startReview(item.id)">
              分享体验
            </button>
          </div>
          <form
            v-if="reviewing"
            class="service-form"
            @submit.prevent="
              send(`/orders/${order.order.id}/reviews`, {
                orderItemId: reviewing,
                ...review,
              })
            "
          >
            <label>
              体验评分
              <select v-model.number="review.stars">
                <option v-for="n in [5, 4, 3, 2, 1]" :key="n" :value="n">
                  {{ n }} / 5
                </option>
              </select>
            </label>
            <label>
              你的体验
              <textarea
                v-model="review.content"
                required
                maxlength="1000"
                rows="3"
                placeholder="写下你对这件作品的感受"
              ></textarea>
            </label>
            <div class="actions">
              <button class="btn" :disabled="busy">提交评价</button>
              <button
                class="btn secondary"
                type="button"
                :disabled="busy"
                @click="reviewing = undefined"
              >
                返回
              </button>
            </div>
          </form>
        </template>
      </div>
    </template>
  </section>
</template>
