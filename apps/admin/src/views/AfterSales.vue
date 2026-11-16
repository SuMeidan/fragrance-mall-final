<script setup lang="ts">
import { computed, onMounted, onScopeDispose, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { query } from '@shared/api'
import { asset, dateTime, money } from '@shared/format'
import {
  saleNames,
  type AfterSale,
  type Capabilities,
  type OrderDetail,
  type Page,
  type Refund,
  type SaleState,
  type Settlement,
} from '@shared/models'
import { api, confirm, fail } from '../state'
const route = useRoute(),
  rows = ref<Page<AfterSale>>(),
  page = ref(1),
  state = ref<SaleState | ''>(''),
  loading = ref(false),
  detailLoading = ref(false),
  busy = ref(false),
  selected = ref<OrderDetail>(),
  funds = ref<Settlement>(),
  caps = ref<Capabilities>(),
  note = ref(''),
  reason = ref('')
const sale = computed(() => funds.value?.afterSale)
const canRestock = computed(
  () =>
    sale.value?.state === 'REFUNDED' &&
    !sale.value.restocked &&
    !!selected.value &&
    selected.value.order.status > 0 &&
    (selected.value.order.status < 3 || !!sale.value.receivedAt),
)
let generation = 0,
  detailGeneration = 0,
  operation = 0
onScopeDispose(() => {
  generation++
  detailGeneration++
  operation++
})
async function load() {
  const current = ++generation
  loading.value = true
  try {
    const result = await api.request<Page<AfterSale>>(
      '/admin/after-sales' + query({ state: state.value, page: page.value }),
    )
    if (current === generation) rows.value = result
  } catch (e) {
    if (current === generation) fail(e)
  } finally {
    if (current === generation) loading.value = false
  }
}
function filter() {
  page.value = 1
  void load()
}
async function show(id: number) {
  const current = ++detailGeneration
  operation++
  busy.value = false
  detailLoading.value = true
  try {
    const [order, settlement, capabilities] = await Promise.all([
      api.request<OrderDetail>(`/admin/orders/${id}`),
      api.request<Settlement>(`/admin/orders/${id}/settlement`),
      api.request<Capabilities>('/capabilities', {}, false),
    ])
    if (current === detailGeneration) {
      selected.value = order
      funds.value = settlement
      caps.value = capabilities
      note.value = ''
      reason.value = ''
    }
  } catch (e) {
    if (current === detailGeneration) fail(e)
  } finally {
    if (current === detailGeneration) detailLoading.value = false
  }
}
function close() {
  detailGeneration++
  operation++
  busy.value = false
  detailLoading.value = false
  selected.value = undefined
  funds.value = undefined
}
async function action(
  name: 'review' | 'receive' | 'refund' | 'restock',
  body?: object,
) {
  if (!sale.value || !selected.value || busy.value) return
  const id = sale.value.id,
    order = selected.value.order.id,
    current = ++operation,
    selectedGeneration = detailGeneration
  if (name === 'review' && !note.value.trim()) {
    fail(new Error('请填写审核说明，退货时请说明退回方式与地址。'))
    return
  }
  if (
    name === 'refund' &&
    !(await confirm(
      `核对平台结果，并按本单实付 ${money(selected.value.order.totalCents)} 处理整单退款？`,
    ))
  )
    return
  if (
    name === 'receive' &&
    !(await confirm('确认已实际收到这笔退货？确认后进入退款核对阶段。'))
  )
    return
  if (
    name === 'restock' &&
    !(await confirm('确认已检验本单全部商品可再次销售？库存将按整单恢复一次。'))
  )
    return
  if (current !== operation || selectedGeneration !== detailGeneration) return
  busy.value = true
  try {
    const result = await api.request<Refund>(
      `/admin/after-sales/${id}/${name}`,
      {
        method: 'POST',
        body: body ? JSON.stringify(body) : undefined,
      },
    )
    if (current !== operation || selectedGeneration !== detailGeneration) return
    if (name === 'refund' && result.status !== 'SUCCESS') {
      ElMessage.warning('退款尚未确认，请查看最新进度并使用原申请核对')
    } else {
      ElMessage.success(
        name === 'refund' ? '平台已确认整单退款' : '已处理，请核对最新进度',
      )
    }
    await show(order)
    await load()
  } catch (e) {
    if (current === operation && selectedGeneration === detailGeneration) {
      // Unknown refund results can still create a stable server-side intent.
      // Refresh that same order so pending is visible before a later retry.
      const refreshGeneration = detailGeneration + 1
      await show(order)
      if (
        detailGeneration === refreshGeneration &&
        selected.value?.order.id === order
      )
        fail(e)
    }
  } finally {
    if (current === operation) busy.value = false
  }
}
function approve(value: boolean) {
  void action('review', { approve: value, note: note.value })
}
function restock() {
  void action('restock', { reason: reason.value })
}
watch(
  () => route.query.orderId,
  (id) => {
    const value = Number(id)
    if (Number.isSafeInteger(value) && value > 0) void show(value)
  },
  { immediate: true },
)
onMounted(load)
</script>
<template>
  <header class="workspace-heading">
    <div>
      <h1>整单售后</h1>
      <p>核对申请、退款与退回商品的处理状态。</p>
    </div>
    <el-button @click="load">刷新</el-button>
  </header>
  <div class="toolbar">
    <el-select
      v-model="state"
      aria-label="售后状态"
      placeholder="全部状态"
      clearable
      @clear="state = ''"
      @change="filter"
    >
      <el-option
        v-for="(label, key) in saleNames"
        :key="key"
        :value="key"
        :label="label"
      />
    </el-select>
  </div>
  <el-table
    v-loading="loading"
    :data="rows?.items || []"
    empty-text="暂无售后申请"
  >
    <el-table-column label="申请ID" prop="id" width="85" />
    <el-table-column label="订单号" prop="orderNo" min-width="240" />
    <el-table-column label="状态" min-width="130">
      <template #default="{ row }">
        <el-tag
          :type="
            row.state === 'REFUNDED'
              ? 'success'
              : row.state === 'REJECTED'
                ? 'info'
                : 'warning'
          "
        >
          {{ saleNames[row.state as SaleState] }}
        </el-tag>
      </template>
    </el-table-column>
    <el-table-column label="整单金额" width="110">
      <template #default="{ row }">{{ money(row.totalCents) }}</template>
    </el-table-column>
    <el-table-column
      label="申请原因"
      prop="reason"
      min-width="220"
      show-overflow-tooltip
    />
    <el-table-column label="申请时间" min-width="175">
      <template #default="{ row }">{{ dateTime(row.createdAt) }}</template>
    </el-table-column>
    <el-table-column label="操作" width="100" fixed="right">
      <template #default="{ row }">
        <el-button link type="primary" @click="show(row.orderId)">
          查看处理
        </el-button>
      </template>
    </el-table-column>
  </el-table>
  <el-pagination
    v-if="rows"
    v-model:current-page="page"
    :page-size="rows.pageSize"
    :total="rows.total"
    layout="total, prev, pager, next"
    @current-change="load"
  />
  <el-drawer
    :model-value="!!selected"
    title="售后处理"
    class="after-sale-drawer"
    @close="close"
  >
    <div v-if="selected && sale" v-loading="detailLoading">
      <div class="sale-summary">
        <div>
          <small>售后编号</small>
          <strong>#{{ sale.id }}</strong>
        </div>
        <div>
          <small>关联订单</small>
          <strong>{{ selected.order.orderNo }}</strong>
        </div>
        <div>
          <small>原订单状态</small>
          <strong>{{ selected.order.statusName }}</strong>
        </div>
        <el-tag :type="sale.state === 'REFUNDED' ? 'success' : 'warning'">
          {{ saleNames[sale.state] }}
        </el-tag>
      </div>
      <div class="sale-layout">
        <div>
          <section class="editor-panel">
            <h2>售后商品</h2>
            <div
              v-for="item in selected.items"
              :key="item.id"
              class="shipment-product"
            >
              <img :src="asset(item.imagePath)" :alt="item.name" />
              <div>
                <strong>{{ item.name }}</strong>
                <small>
                  {{ item.spec }} · {{ item.quantity }} {{ item.unit }}
                </small>
              </div>
              <b>{{ money(item.priceCents * item.quantity) }}</b>
            </div>
            <div class="drawer-section">
              <h3>申请原因</h3>
              <p class="admin-note preserve">{{ sale.reason }}</p>
            </div>
          </section>
          <section class="editor-panel">
            <h2>处理进度</h2>
            <h3>{{ saleNames[sale.state] }}</h3>
            <dl class="detail-list">
              <dt>申请时间</dt>
              <dd>{{ dateTime(sale.createdAt) }}</dd>
              <dt>审核说明</dt>
              <dd class="preserve">{{ sale.reviewNote || '尚待审核' }}</dd>
              <dt>退回物流</dt>
              <dd>
                {{ sale.returnCarrier || '—' }} {{ sale.returnTrackingNo }}
              </dd>
              <dt>收到退回</dt>
              <dd>{{ dateTime(sale.receivedAt) }}</dd>
              <dt>退款</dt>
              <dd>
                {{ funds?.refund?.status || '尚未发起' }}
                <span v-if="funds?.refund?.status === 'SUCCESS'">
                  · {{ money(funds.refund.amountCents) }} ·
                  {{ dateTime(funds.refund.successAt) }}
                </span>
              </dd>
              <template v-if="funds?.refund?.lastErrorCode">
                <dt>核对提示</dt>
                <dd>{{ funds.refund.lastErrorCode }}</dd>
              </template>
            </dl>
          </section>
        </div>
        <div>
          <section class="editor-panel">
            <h2>
              {{
                sale.state === 'REQUESTED'
                  ? '审核申请'
                  : sale.state === 'RETURN_REQUIRED'
                    ? '退货验收'
                    : '退款处理'
              }}
            </h2>
            <div class="sale-amount">
              <small>已确认实付</small>
              <strong>
                {{
                  funds?.payment?.status === 'SUCCESS'
                    ? money(funds.payment.amountCents || 0)
                    : '尚未确认'
                }}
              </strong>
            </div>
            <form
              v-if="sale.state === 'REQUESTED'"
              @submit.prevent="approve(true)"
            >
              <label>
                审核说明
                <el-input
                  v-model="note"
                  type="textarea"
                  required
                  :rows="4"
                  maxlength="500"
                  placeholder="请说明理由；退货时请写清退回方式和地址"
                />
              </label>
              <div class="dialog-actions">
                <el-button :disabled="busy" @click="approve(false)">
                  驳回申请
                </el-button>
                <el-button type="primary" native-type="submit" :loading="busy">
                  同意整单售后
                </el-button>
              </div>
            </form>
            <template v-if="sale.state === 'RETURN_REQUIRED'">
              <p class="admin-note">
                核对退回实物与退货物流后，单独确认收货；收货不会自动退款。
              </p>
              <el-button
                :disabled="!sale.returnTrackingNo"
                :loading="busy"
                type="primary"
                @click="action('receive')"
              >
                确认收到退回商品
              </el-button>
            </template>
            <template v-if="sale.state === 'REFUND_PENDING'">
              <p class="note-surface">
                请核对退货与整单实付金额。退款结果以支付渠道确认为准。
              </p>
              <el-alert
                v-if="!caps?.paymentConfigured"
                title="支付沙箱尚未配置，暂不能发起或核对退款。"
                type="warning"
                :closable="false"
              />
              <div class="dialog-actions">
                <el-button
                  :disabled="!caps?.paymentConfigured"
                  :loading="busy"
                  type="primary"
                  @click="action('refund')"
                >
                  核对并处理整单退款
                </el-button>
              </div>
            </template>
            <p
              v-if="sale.state === 'REJECTED' || sale.state === 'REFUNDED'"
              class="admin-note"
            >
              {{
                sale.state === 'REJECTED'
                  ? '本次申请已驳回。'
                  : '本次整单退款已确认，请核对下方库存处理状态。'
              }}
            </p>
          </section>
          <section class="editor-panel">
            <h2>库存回补</h2>
            <form v-if="canRestock" @submit.prevent="restock">
              <label>
                可售入库原因
                <el-input
                  v-model="reason"
                  required
                  maxlength="120"
                  placeholder="记录实物验收与可再次销售的依据"
                />
              </label>
              <div class="dialog-actions">
                <el-button native-type="submit" :loading="busy" type="primary">
                  确认可售并入库
                </el-button>
              </div>
            </form>
            <p v-else class="sale-disabled">
              {{
                sale.restocked
                  ? '已恢复一次'
                  : selected.order.status < 0
                    ? '关闭时已恢复，不重复入库'
                    : '尚未恢复'
              }}
            </p>
            <p class="admin-note">
              退款成功、满足实物验收条件后，单独确认可售入库；每笔售后仅恢复一次。
            </p>
          </section>
          <div class="dialog-actions">
            <el-button
              :disabled="busy || detailLoading"
              @click="show(selected.order.id)"
            >
              刷新本单
            </el-button>
          </div>
        </div>
      </div>
    </div>
    <el-empty v-else-if="selected" description="此订单尚无售后申请" />
  </el-drawer>
</template>
