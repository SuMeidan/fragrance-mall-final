<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { Category } from '@shared/models'
import { ElMessage } from 'element-plus'
import { api, fail } from '../state'
const rows = ref<Category[]>([]),
  editing = ref<Category>(),
  loading = ref(false),
  saving = ref(false)
const roots = computed(() => rows.value.filter((c) => !c.parentId))
function edit(row: Category) {
  editing.value = { ...row }
}
function reset() {
  const row = rows.value.find((c) => c.id === editing.value?.id)
  if (row) edit(row)
}
async function load() {
  loading.value = true
  try {
    rows.value = await api.request<Category[]>('/admin/categories')
    const next =
      rows.value.find((c) => c.id === editing.value?.id) ||
      rows.value.find((c) => !!c.parentId) ||
      rows.value[0]
    if (next) edit(next)
  } catch (e) {
    fail(e)
  } finally {
    loading.value = false
  }
}
async function save() {
  if (!editing.value) return
  saving.value = true
  const { id, name, sort, enabled } = editing.value
  try {
    await api.request(`/admin/categories/${id}`, {
      method: 'PATCH',
      body: JSON.stringify({ name, sort, enabled }),
    })
    // Keep the saved category selected in its context.
    ElMessage.success('分类已保存')
    await load()
  } catch (e) {
    fail(e)
  } finally {
    saving.value = false
  }
}
onMounted(load)
</script>
<template>
  <header class="workspace-heading">
    <div>
      <h1>商品分类</h1>
      <p>
        {{ roots.length }} 个一级分类 ·
        {{ rows.filter((c) => !!c.parentId).length }} 个商品品类。
      </p>
    </div>
    <el-button :loading="loading" @click="load">刷新</el-button>
  </header>
  <div class="category-layout" v-loading="loading">
    <div class="category-tree" aria-label="商品分类树">
      <section v-for="root in roots" :key="root.id" class="category-group">
        <div class="category-group-heading">
          <button :aria-pressed="editing?.id === root.id" @click="edit(root)">
            {{ root.name }}
          </button>
          <small>
            {{ rows.filter((c) => c.parentId === root.id).length }} 个品类
          </small>
        </div>
        <button
          v-for="leaf in rows.filter((c) => c.parentId === root.id)"
          :key="leaf.id"
          class="category-option"
          :data-category-id="leaf.id"
          :aria-pressed="editing?.id === leaf.id"
          @click="edit(leaf)"
        >
          <span>
            {{ leaf.name }}
            <span v-if="!leaf.enabled" class="table-muted">已停用</span>
          </span>
          <small>{{ leaf.code }}</small>
        </button>
      </section>
    </div>
    <section v-if="editing" class="category-editor" aria-label="编辑分类">
      <p class="eyebrow">
        {{ rows.find((c) => c.id === editing?.parentId)?.name || '一级分类' }} /
        {{ editing.code }}
      </p>
      <h2>{{ rows.find((c) => c.id === editing?.id)?.name }}</h2>
      <p class="admin-note">修改名称、显示顺序与启用状态。</p>
      <form @submit.prevent="save">
        <label for="category-name">
          分类名称
          <el-input
            id="category-name"
            v-model="editing.name"
            required
            maxlength="40"
          />
        </label>
        <label for="category-sort">
          显示顺序
          <el-input-number
            id="category-sort"
            v-model="editing.sort"
            :min="0"
            :max="9999"
            :precision="0"
          />
        </label>
        <label for="category-enabled">
          启用分类
          <el-switch
            id="category-enabled"
            v-model="editing.enabled"
            aria-label="启用分类"
          />
        </label>
        <p class="note-surface">停用后，此分类下的商品将不在商城展示。</p>
        <div class="dialog-actions">
          <el-button :disabled="saving" @click="reset">恢复</el-button>
          <el-button native-type="submit" type="primary" :loading="saving">
            保存修改
          </el-button>
        </div>
      </form>
    </section>
    <el-empty v-else-if="!loading" description="暂无分类" />
  </div>
</template>
