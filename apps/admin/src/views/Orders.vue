<script setup lang="ts">
import { onMounted, onScopeDispose, reactive, ref } from 'vue'
import { useOrderTime } from '@shared/order-time'
import type { Order, OrderDetail, Page } from '@shared/models'
import { query } from '@shared/api'
import { addressText, asset, dateTime, money } from '@shared/format'
import { api, confirm, fail } from '../state'
import OrderSettlement from '../components/OrderSettlement.vue'
const data = ref<Page<Order>>(),
  detail = ref<OrderDetail>(),
  selectedId = ref<number>(),
  detailLoading = ref(false),
  page = ref(1),
  status = ref<number | ''>(''),
  loading = ref(false),
  busy = ref(false),
  shipping = ref(false),
  shipment = reactive({ carrier: '', trackingNo: '' })
const isExpired = useOrderTime()
let generation = 0,
  selection = 0,
  operation = 0
onScopeDispose(() => {
  generation++
  selection++
  operation++
})
const statuses = [
  { code: 0, name: '待付款' },
  { code: 1, name: '已支付' },
  { code: 2, name: '配货中' },
  { code: 3, name: '已发货' },
  { code: 4, name: '已完成' },
  { code: -1, name: '买家关闭' },
  { code: -2, name: '超时关闭' },
  { code: -3, name: '后台关闭' },
]
async function load() {
  const current = ++generation
  loading.value = true
  try {
    const result = await api.request<Page<Order>>(
      '/admin/orders' + query({ status: status.value, page: page.value }),
    )
    if (current === generation) data.value = result
  } catch (e) {
    if (current === generation) fail(e)
  } finally {
    if (current === generation) loading.value = false
  }
}
async function show(id: number) {
  const current = ++selection
  operation++
  busy.value = false
  selectedId.value = id
  detailLoading.value = true
  detail.value = undefined
  shipping.value = false
  try {
    const result = await api.request<OrderDetail>(`/admin/orders/${id}`)
    if (current === selection) {
      detail.value = result
      Object.assign(shipment, { carrier: '', trackingNo: '' })
    }
  } catch (e) {
    if (current === selection) fail(e)
  } finally {
    if (current === selection) detailLoading.value = false
  }
}
async function action(name: 'pack' | 'cancel' | 'ship') {
  if (!detail.value || busy.value) return
  const id = detail.value.order.id,
    current = ++operation,
    selected = selection
  if (
    name === 'cancel' &&
    !(await confirm('确认申请关闭？服务端核对平台结果，确认关闭后才恢复库存。'))
  )
    return
  if (current !== operation || selected !== selection) return
  busy.value = true
  try {
    const result = await api.request<OrderDetail>(
      `/admin/orders/${id}/${name}`,
      {
        method: 'POST',
        body: name === 'ship' ? JSON.stringify(shipment) : undefined,
      },
    )
    if (current !== operation || selected !== selection) return
    detail.value = result
    shipping.value = false
    await load()
  } catch (e) {
    if (current === operation && selected === selection) {
      // The bank can confirm a payment while a close request is in flight.
      // Re-read that order so the drawer stops offering stale actions.
      try {
        const latest = await api.request<OrderDetail>(`/admin/orders/${id}`)
        if (current === operation && selected === selection)
          detail.value = latest
      } catch {
        /* Keep the original operation error. */
      }
      if (current === operation && selected === selection) fail(e)
    }
  } finally {
    if (current === operation) busy.value = false
  }
}
onMounted(load)
function filterStatus() {
  page.value = 1
  void load()
}
function closeDetail() {
  selection++
  operation++
  busy.value = false
  detail.value = undefined
  selectedId.value = undefined
  detailLoading.value = false
  shipping.value = false
}
</script>
<template>
  <header class="workspace-heading">
    <div>
      <h1>订单管理</h1>
      <p>查看订单状态，处理配货与发货。</p>
    </div>
    <el-button @click="load">刷新</el-button>
  </header>
  <div class="toolbar">
    <el-select
      v-model="status"
      aria-label="订单状态"
      placeholder="全部状态"
      clearable
      @clear="status = ''"
      @change="filterStatus"
    >
      <el-option
        v-for="s in statuses"
        :key="s.code"
        :value="s.code"
        :label="s.name"
      />
    </el-select>
  </div>
  <el-table v-loading="loading" :data="data?.items || []" empty-text="暂无订单">
    <el-table-column label="订单编号" prop="orderNo" min-width="240" />
    <el-table-column label="创建时间" min-width="175">
      <template #default="{ row }">{{ dateTime(row.createdAt) }}</template>
    </el-table-column>
    <el-table-column label="买家" min-width="150">
      <template #default="{ row }">
        {{ row.receiver || `买家 #${row.accountId}` }}
        <small v-if="row.phone" class="table-muted">
          {{ row.phone.replace(/^(\d{3})\d+(\d{4})$/, '$1****$2') }}
        </small>
      </template>
    </el-table-column>
    <el-table-column label="数量" prop="itemCount" width="70" />
    <el-table-column label="订单金额" width="115">
      <template #default="{ row }">{{ money(row.totalCents) }}</template>
    </el-table-column>
    <el-table-column label="状态" width="115">
      <template #default="{ row }">
        <el-tag
          :type="
            row.status === 4 ? 'success' : row.status > 0 ? 'warning' : 'info'
          "
        >
          {{ isExpired(row) ? '付款时间已过' : row.statusName }}
        </el-tag>
      </template>
    </el-table-column>
    <el-table-column label="操作" width="105" fixed="right">
      <template #default="{ row }">
        <el-button link type="primary" @click="show(row.id)">
          查看处理
        </el-button>
      </template>
    </el-table-column>
  </el-table>
  <el-pagination
    v-if="data"
    v-model:current-page="page"
    :page-size="data.pageSize"
    :total="data.total"
    layout="total, prev, pager, next"
    @current-change="load"
  />
  <el-drawer
    :model-value="selectedId !== undefined"
    title="订单详情"
    size="min(626px, 100%)"
    @close="closeDetail"
  >
    <p v-if="detailLoading" class="admin-note" role="status">正在读取订单…</p>
    <template v-if="detail">
      <div class="drawer-status">
        <el-tag
          :type="
            detail.order.status === 4
              ? 'success'
              : detail.order.status > 0
                ? 'warning'
                : 'info'
          "
        >
          {{
            isExpired(detail.order) ? '付款时间已过' : detail.order.statusName
          }}
        </el-tag>
        <span>{{ dateTime(detail.order.createdAt) }}</span>
      </div>
      <el-alert
        v-if="isExpired(detail.order)"
        title="付款期限已过。付款与库存继续按平台核对结果处理，请先查询付款结果。"
        type="warning"
        :closable="false"
      />
      <h2>{{ detail.order.orderNo }}</h2>
      <section class="drawer-section" style="margin-top: 24px">
        <h3>本次寄送</h3>
        <div
          v-for="item in detail.items"
          :key="item.id"
          class="shipment-product"
        >
          <img :src="asset(item.imagePath)" :alt="item.name" />
          <div>
            <strong>{{ item.name }}</strong>
            <small>
              {{ item.spec }} · {{ money(item.priceCents) }} ×
              {{ item.quantity }}
            </small>
          </div>
          <b>{{ money(item.priceCents * item.quantity) }}</b>
        </div>
        <div class="amount-line">
          <span>运费</span>
          <span>¥0.00 · 包邮</span>
        </div>
        <div class="amount-line">
          <strong>订单金额</strong>
          <strong>{{ money(detail.order.totalCents) }}</strong>
        </div>
      </section>
      <section class="drawer-section">
        <h3>收货信息</h3>
        <p class="preserve" style="font-size: 13px; line-height: 1.9">
          {{ addressText(detail.order) }}
        </p>
        <p class="admin-note">买家 #{{ detail.order.accountId }}</p>
      </section>
      <section
        v-if="detail.order.carrier || detail.order.closeReason"
        class="drawer-section"
      >
        <h3>{{ detail.order.closeReason ? '订单关闭' : '物流信息' }}</h3>
        <p class="admin-note">
          {{
            detail.order.closeReason ||
            `${detail.order.carrier} ${detail.order.trackingNo || ''}`
          }}
        </p>
      </section>
      <div class="dialog-actions">
        <el-button
          v-if="detail.order.status === 0"
          type="danger"
          plain
          :loading="busy"
          @click="action('cancel')"
        >
          关闭未付订单
        </el-button>
        <el-button
          v-if="detail.order.status === 1"
          type="primary"
          :loading="busy"
          @click="action('pack')"
        >
          开始配货
        </el-button>
        <el-button
          v-if="[1, 2].includes(detail.order.status)"
          type="primary"
          @click="shipping = true"
        >
          填写发货信息
        </el-button>
        <el-button @click="show(detail.order.id)">刷新状态</el-button>
      </div>
      <form
        v-if="shipping"
        class="drawer-section"
        style="margin-top: 24px"
        @submit.prevent="action('ship')"
      >
        <h3>填写物流</h3>
        <div class="form-grid">
          <label>
            承运方
            <el-input v-model="shipment.carrier" required maxlength="40" />
          </label>
          <label>
            运单号
            <el-input
              v-model="shipment.trackingNo"
              required
              pattern="[A-Za-z0-9\-]{6,64}"
              minlength="6"
              maxlength="64"
            />
          </label>
        </div>
        <p class="admin-note">
          请核对承运方与运单号。提交后订单将更新为已发货。
        </p>
        <div class="dialog-actions">
          <el-button @click="shipping = false">取消</el-button>
          <el-button native-type="submit" type="primary" :loading="busy">
            确认发货
          </el-button>
        </div>
      </form>
      <OrderSettlement
        :key="`${detail.order.id}:${detail.order.status}`"
        :order-id="detail.order.id"
        @updated="show(detail.order.id)"
      />
    </template>
  </el-drawer>
</template>
