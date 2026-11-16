<script setup lang="ts">
import { onScopeDispose, ref, watch } from 'vue'
import { dateTime, money } from '@shared/format'
import { errorText } from '@shared/api'
import { saleNames, type Capabilities, type Settlement } from '@shared/models'
import { api } from '../state'
const props = defineProps<{ orderId: number }>()
const emit = defineEmits<{ updated: [] }>()
const data = ref<Settlement>(),
  caps = ref<Capabilities>(),
  loading = ref(false),
  error = ref(''),
  busy = ref(false)
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
    const [state, capabilities] = await Promise.all([
      api.request<Settlement>(`/admin/orders/${props.orderId}/settlement`),
      api.request<Capabilities>('/capabilities', {}, false),
    ])
    if (current === generation) {
      data.value = state
      caps.value = capabilities
    }
  } catch (e) {
    if (current === generation) error.value = errorText(e)
  } finally {
    if (current === generation) loading.value = false
  }
}
async function synchronize() {
  if (busy.value) return
  const current = ++operation,
    id = props.orderId
  busy.value = true
  error.value = ''
  try {
    await api.request(`/admin/orders/${id}/payment/sync`, {
      method: 'POST',
    })
    if (current === operation) {
      await load()
      if (current === operation) emit('updated')
    }
  } catch (e) {
    if (current === operation) error.value = errorText(e)
  } finally {
    if (current === operation) busy.value = false
  }
}
watch(
  () => props.orderId,
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
  <section class="settlement-panel" v-loading="loading">
    <h3>资金与售后</h3>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="data">
      <el-alert
        v-if="data.payment?.needsReview"
        title="关闭订单后确认收款：请核对整单退款，不重复发货或恢复库存。"
        type="warning"
        :closable="false"
      />
      <el-alert
        v-if="data.payment?.status === 'PENDING'"
        title="付款结果待核对。平台繁忙或暂时查不到交易时，保留原订单与库存，查询确认后再处理。"
        type="info"
        :closable="false"
      />
      <dl class="detail-list">
        <dt>付款</dt>
        <dd>
          {{
            !data.payment
              ? '尚未发起'
              : data.payment.status === 'SUCCESS'
                ? '已确认收款'
                : data.payment.status === 'CLOSED'
                  ? '平台已关闭'
                  : '待平台核对'
          }}
        </dd>
        <template v-if="data.payment?.status === 'SUCCESS'">
          <dt>实付</dt>
          <dd>
            {{ money(data.payment.amountCents || 0) }} ·
            {{ dateTime(data.payment.successAt) }}
          </dd>
          <dt>平台单号</dt>
          <dd>{{ data.payment.tradeNo }}</dd>
        </template>
        <dt>售后</dt>
        <dd>
          {{ data.afterSale ? saleNames[data.afterSale.state] : '无申请' }}
          <RouterLink
            v-if="data.afterSale"
            class="admin-link"
            :to="{ path: '/after-sales', query: { orderId } }"
          >
            查看处理
          </RouterLink>
        </dd>
        <dt>退款</dt>
        <dd>
          {{
            data.refund
              ? data.refund.status === 'SUCCESS'
                ? `已确认 ${money(data.refund.amountCents)}`
                : '尚待核对'
              : '尚未发起'
          }}
        </dd>
      </dl>
      <el-button
        v-if="data.payment"
        :disabled="!caps?.paymentConfigured"
        :loading="busy"
        @click="synchronize"
      >
        查询平台付款结果
      </el-button>
      <p v-if="!caps?.paymentConfigured" class="admin-note">
        支付沙箱尚未配置，当前无法向平台查单或退款。
      </p>
    </template>
  </section>
</template>
