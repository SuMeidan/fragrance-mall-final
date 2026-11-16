<!-- List/edit/stock workflow adapted from newbee-mall-vue3-admin Good.vue and AddGood.vue.
     Copyright (c) newbee-ltd; GPL-3.0-or-later. Upstream commit: 3072a2b1b5faed9bc42d45b0955c41bbea47a4d9; adaptation details in the project README. -->
<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { Page, Product, ProductDetail } from '@shared/models'
import { query } from '@shared/api'
import { money, asset } from '@shared/format'
import { api, fail } from '../state'
import ProductEditor from '../components/ProductEditor.vue'
const data = ref<Page<Product>>(),
  keyword = ref(''),
  page = ref(1),
  loading = ref(false),
  editing = ref<number | null | undefined>(),
  stockProduct = ref<Product>(),
  delta = ref(1),
  reason = ref(''),
  saving = ref(false)
let generation = 0
onBeforeUnmount(() => generation++)
async function load() {
  const current = ++generation
  loading.value = true
  try {
    const result = await api.request<Page<Product>>(
      '/admin/products' + query({ keyword: keyword.value, page: page.value }),
    )
    if (current === generation) data.value = result
  } catch (e) {
    if (current === generation) fail(e)
  } finally {
    if (current === generation) loading.value = false
  }
}
async function stock(p: Product) {
  try {
    stockProduct.value = (
      await api.request<ProductDetail>(`/admin/products/${p.id}`)
    ).product
    delta.value = 1
    reason.value = ''
  } catch (e) {
    fail(e)
  }
}
async function saveStock() {
  const p = stockProduct.value
  if (!p) return
  saving.value = true
  try {
    await api.request(`/admin/products/${p.id}/stock`, {
      method: 'POST',
      body: JSON.stringify({
        version: p.version,
        delta: delta.value,
        reason: reason.value,
      }),
    })
    stockProduct.value = undefined
    ElMessage.success('库存已调整')
    await load()
  } catch (e) {
    fail(e)
  } finally {
    saving.value = false
  }
}
onMounted(load)
function search() {
  page.value = 1
  void load()
}
function clearSearch() {
  keyword.value = ''
  search()
}
function saved() {
  editing.value = undefined
  void load()
}
</script>
<template>
  <header class="workspace-heading">
    <div>
      <h1>商品管理</h1>
      <p>管理作品信息、售价与上架状态。</p>
    </div>
    <el-button type="primary" @click="editing = null">新增商品</el-button>
  </header>
  <form class="toolbar" @submit.prevent="search">
    <el-input
      v-model="keyword"
      placeholder="搜索商品名称"
      aria-label="搜索商品"
      clearable
      maxlength="100"
    />
    <el-button native-type="submit" type="primary" :loading="loading">
      查询
    </el-button>
    <el-button @click="clearSearch">清空</el-button>
    <span v-if="data" class="toolbar-count">{{ data.total }} 件作品</span>
  </form>
  <el-table v-loading="loading" :data="data?.items || []" empty-text="暂无商品">
    <el-table-column label="商品" min-width="260">
      <template #default="{ row }">
        <div class="table-product">
          <img :src="asset(row.imagePath)" :alt="row.name" />
          <div>
            <strong>{{ row.name }}</strong>
            <small>#{{ row.id }} · {{ row.spec }}</small>
          </div>
        </div>
      </template>
    </el-table-column>
    <el-table-column label="品类" prop="categoryName" min-width="150" />
    <el-table-column label="香型" min-width="100">
      <template #default="{ row }">
        {{ row.inputView === 'TOOL' ? '不适用' : row.familyName || '—' }}
      </template>
    </el-table-column>
    <el-table-column label="售价" min-width="100">
      <template #default="{ row }">{{ money(row.priceCents) }}</template>
    </el-table-column>
    <el-table-column label="库存" prop="stock" width="80" />
    <el-table-column label="状态" width="90">
      <template #default="{ row }">
        <el-tag :type="row.active ? 'success' : 'info'">
          {{ row.active ? '上架' : '下架' }}
        </el-tag>
      </template>
    </el-table-column>
    <el-table-column label="操作" width="165" fixed="right">
      <template #default="{ row }">
        <el-button link type="primary" @click="editing = row.id">
          编辑
        </el-button>
        <el-button link type="primary" @click="stock(row)">调整库存</el-button>
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
  <ProductEditor
    v-if="editing !== undefined"
    :id="editing"
    @close="editing = undefined"
    @saved="saved"
  />
  <el-dialog
    :model-value="!!stockProduct"
    title="调整库存"
    width="570px"
    :close-on-click-modal="false"
    @close="stockProduct = undefined"
  >
    <form @submit.prevent="saveStock">
      <div v-if="stockProduct" class="stock-product">
        <img :src="asset(stockProduct.imagePath)" :alt="stockProduct.name" />
        <div>
          <h3>{{ stockProduct.name }}</h3>
          <small>#{{ stockProduct.id }} · {{ stockProduct.spec }}</small>
        </div>
        <b>
          {{ stockProduct.stock }}
          <small>当前库存</small>
        </b>
      </div>
      <div class="form-grid">
        <label for="stock-delta">
          库存变化量
          <el-input-number
            id="stock-delta"
            aria-label="库存变化量"
            v-model="delta"
            :min="-1000000"
            :max="1000000"
            :precision="0"
          />
          <small class="field-hint">正数补入，负数扣减。</small>
        </label>
        <label>
          调整后库存
          <output class="stock-preview" aria-live="polite">
            {{ (stockProduct?.stock || 0) + (delta || 0) }}
          </output>
        </label>
      </div>
      <label for="stock-reason">
        调整原因
        <el-input
          id="stock-reason"
          v-model="reason"
          required
          maxlength="120"
          type="textarea"
          :rows="3"
          placeholder="记录本次调整的具体原因"
        />
      </label>
      <p class="note-surface">确认后将记录本次库存变化与操作人。</p>
      <div class="dialog-actions">
        <el-button :disabled="saving" @click="stockProduct = undefined">
          取消
        </el-button>
        <el-button
          type="primary"
          native-type="submit"
          :disabled="
            !delta ||
            !reason.trim() ||
            (stockProduct?.stock || 0) + (delta || 0) < 0
          "
          :loading="saving"
        >
          确认调整
        </el-button>
      </div>
    </form>
  </el-dialog>
</template>
