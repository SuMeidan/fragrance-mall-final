<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { Finance } from '@shared/models'
import { money, todayShanghai } from '@shared/format'
import { errorText, query } from '@shared/api'
import { api } from '../state'
const today = todayShanghai(),
  from = ref(today.slice(0, 8) + '01'),
  to = ref(today),
  data = ref<Finance>(),
  loading = ref(false),
  error = ref('')
let generation = 0
const paymentCount = computed(
  () => data.value?.days.reduce((sum, day) => sum + day.paymentCount, 0) || 0,
)
const refundCount = computed(
  () => data.value?.days.reduce((sum, day) => sum + day.refundCount, 0) || 0,
)
async function load() {
  const current = ++generation
  loading.value = true
  error.value = ''
  data.value = undefined
  try {
    const result = await api.request<Finance>(
      '/admin/finance' + query({ from: from.value, to: to.value }),
    )
    if (current === generation) data.value = result
  } catch (e) {
    if (current === generation) error.value = errorText(e)
  } finally {
    if (current === generation) loading.value = false
  }
}
onMounted(load)
</script>
<template>
  <header class="workspace-heading">
    <div>
      <h1>成功日账目</h1>
      <p>按上海日期分别统计成功收款与退款，净收款不等于利润或银行到账。</p>
    </div>
  </header>
  <form class="finance-toolbar" @submit.prevent="load">
    <label>
      开始日期
      <input v-model="from" type="date" required :max="to" />
    </label>
    <label>
      结束日期
      <input v-model="to" type="date" required :min="from" />
    </label>
    <el-button type="primary" native-type="submit" :loading="loading">
      查询
    </el-button>
    <span class="finance-timezone">北京时间 · UTC+8</span>
  </form>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <template v-if="data">
    <p class="admin-note">
      {{ data.from }} 至 {{ data.to }} · 支付沙箱记录，待核对和失败款项不计入。
    </p>
    <div class="finance-totals">
      <section>
        <p>成功收款</p>
        <strong>{{ money(data.paidCents) }}</strong>
        <small>{{ paymentCount }} 笔成功收款</small>
      </section>
      <section>
        <p>成功退款</p>
        <strong>{{ money(data.refundedCents) }}</strong>
        <small>{{ refundCount }} 笔成功退款</small>
      </section>
      <section>
        <p>净收款</p>
        <strong>{{ money(data.netCents) }}</strong>
        <small>成功收款 − 成功退款</small>
      </section>
    </div>
    <section class="finance-detail">
      <h2>每日明细</h2>
      <el-table
        v-loading="loading"
        :data="data.days"
        empty-text="当前区间暂无记录"
      >
        <el-table-column label="日期（上海）" prop="date" min-width="135" />
        <el-table-column label="成功收款" min-width="120">
          <template #default="{ row }">{{ money(row.paidCents) }}</template>
        </el-table-column>
        <el-table-column label="收款笔数" prop="paymentCount" width="100" />
        <el-table-column label="成功退款" min-width="120">
          <template #default="{ row }">{{ money(row.refundedCents) }}</template>
        </el-table-column>
        <el-table-column label="退款笔数" prop="refundCount" width="100" />
        <el-table-column label="净收款" min-width="125">
          <template #default="{ row }">
            <span :class="{ 'negative-amount': row.netCents < 0 }">
              {{ money(row.netCents) }}
            </span>
          </template>
        </el-table-column>
      </el-table>
    </section>
  </template>
</template>
