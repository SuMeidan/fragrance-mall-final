<script setup lang="ts">
import { onMounted, ref } from 'vue'
import type { Audit, Page } from '@shared/models'
import { dateTime } from '@shared/format'
import { api, fail } from '../state'
const data = ref<Page<Audit>>(),
  page = ref(1),
  loading = ref(false)
async function load() {
  loading.value = true
  try {
    data.value = await api.request<Page<Audit>>(
      `/admin/audits?page=${page.value}`,
    )
  } catch (e) {
    fail(e)
  } finally {
    loading.value = false
  }
}
onMounted(load)
</script>
<template>
  <header class="workspace-heading">
    <div>
      <h1>操作记录</h1>
      <p>关键管理操作会留存于此，供核对与追溯。</p>
    </div>
    <el-button @click="load">刷新</el-button>
  </header>
  <el-table v-loading="loading" :data="data?.items || []" empty-text="暂无记录">
    <el-table-column label="时间" width="185">
      <template #default="{ row }">{{ dateTime(row.createdAt) }}</template>
    </el-table-column>
    <el-table-column prop="actorId" label="操作者ID" width="100" />
    <el-table-column prop="action" label="动作" min-width="170" />
    <el-table-column label="对象" min-width="130">
      <template #default="{ row }">
        {{ row.resourceType }} #{{ row.resourceId }}
      </template>
    </el-table-column>
    <el-table-column
      prop="summary"
      label="摘要"
      min-width="220"
      show-overflow-tooltip
    />
    <el-table-column prop="traceId" label="追踪号" min-width="280" />
  </el-table>
  <el-pagination
    v-if="data"
    v-model:current-page="page"
    :page-size="data.pageSize"
    :total="data.total"
    layout="total, prev, pager, next"
    @current-change="load"
  />
</template>
