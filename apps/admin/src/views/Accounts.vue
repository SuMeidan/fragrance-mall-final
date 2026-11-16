<script setup lang="ts">
import { onMounted, ref } from 'vue'
import type { Account, Page } from '@shared/models'
import { query } from '@shared/api'
import { api, confirm, fail } from '../state'
const data = ref<Page<Account>>(),
  page = ref(1),
  keyword = ref(''),
  loading = ref(false),
  busy = ref(false)
async function load() {
  loading.value = true
  try {
    data.value = await api.request<Page<Account>>(
      '/admin/accounts' + query({ page: page.value, keyword: keyword.value }),
    )
  } catch (e) {
    fail(e)
  } finally {
    loading.value = false
  }
}
async function toggle(a: Account) {
  if (
    !(await confirm(
      `${a.displayName || '买家'}（${a.loginName.replace(/^(\d{3})\d+(\d{4})$/, '$1****$2')}）\n${a.enabled ? '停用后，该买家当前登录将失效，且无法再次登录。已产生的订单与交易记录将保留。' : '恢复后，该买家可以重新登录。已产生的订单与交易记录保持。'}`,
      a.enabled ? '停用这个买家账号？' : '恢复这个买家账号？',
      a.enabled ? '确认停用' : '确认恢复',
      a.enabled,
    ))
  )
    return
  busy.value = true
  try {
    await api.request(`/admin/accounts/${a.id}`, {
      method: 'PATCH',
      body: JSON.stringify({ enabled: !a.enabled }),
    })
    await load()
  } catch (e) {
    fail(e)
  } finally {
    busy.value = false
  }
}
onMounted(load)
function search() {
  page.value = 1
  void load()
}
</script>
<template>
  <header class="workspace-heading">
    <div>
      <h1>买家管理</h1>
      <p>查询买家账号，管理访问状态。</p>
    </div>
    <el-button @click="load">刷新</el-button>
  </header>
  <form class="toolbar" @submit.prevent="search">
    <el-input
      v-model="keyword"
      placeholder="按手机号搜索"
      aria-label="买家手机号"
      maxlength="32"
      clearable
    />
    <el-button native-type="submit" :loading="loading">查询</el-button>
  </form>
  <el-table v-loading="loading" :data="data?.items || []" empty-text="暂无买家">
    <el-table-column prop="id" label="ID" width="100" />
    <el-table-column label="手机号" min-width="150">
      <template #default="{ row }">
        {{ row.loginName.replace(/^(\d{3})\d+(\d{4})$/, '$1****$2') }}
      </template>
    </el-table-column>
    <el-table-column prop="displayName" label="称呼" min-width="150" />
    <el-table-column label="状态" width="120">
      <template #default="{ row }">
        <el-tag :type="row.enabled ? 'success' : 'info'">
          {{ row.enabled ? '正常' : '停用' }}
        </el-tag>
      </template>
    </el-table-column>
    <el-table-column label="操作" width="120">
      <template #default="{ row }">
        <el-button
          link
          :type="row.enabled ? 'danger' : 'primary'"
          :disabled="busy"
          @click="toggle(row)"
        >
          {{ row.enabled ? '停用' : '恢复' }}
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
</template>
