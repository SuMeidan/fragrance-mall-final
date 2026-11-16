<script setup lang="ts">
import { ref, watch } from 'vue'
import type { Page, Review } from '@shared/models'
import { errorText } from '@shared/api'
import { dateTime } from '@shared/format'
import { api } from '../state'
const props = defineProps<{ productId: number }>()
const data = ref<Page<Review>>(),
  loading = ref(false),
  error = ref(''),
  page = ref(1)
let generation = 0
async function load() {
  const current = ++generation
  loading.value = true
  error.value = ''
  try {
    const result = await api.request<Page<Review>>(
      `/catalog/products/${props.productId}/reviews?page=${page.value}`,
      {},
      false,
    )
    if (current === generation) data.value = result
  } catch (e) {
    if (current === generation) error.value = errorText(e)
  } finally {
    if (current === generation) loading.value = false
  }
}
function turn(delta: number) {
  page.value += delta
  void load()
}
watch(
  () => props.productId,
  () => {
    page.value = 1
    load()
  },
  { immediate: true },
)
</script>
<template>
  <section class="product-reviews section">
    <div class="section-head">
      <div>
        <p class="eyebrow">SHARED EXPERIENCES</p>
        <h2>关于它的感受</h2>
      </div>
      <span class="quiet">{{ data?.total || 0 }} 条评价</span>
    </div>
    <p v-if="loading" class="quiet" role="status">正在读取评价…</p>
    <p v-if="error" class="form-error" role="alert">
      {{ error }}
      <button class="text-link" @click="load">重试</button>
    </p>
    <p v-if="!loading && !error && !data?.total" class="quiet">
      还没有留下的体验，期待你的感受。
    </p>
    <article v-for="r in data?.items" :key="r.id">
      <header>
        <strong>{{ r.displayName }}</strong>
        <span :aria-label="`${r.stars}分，满分5分`">
          {{ '★'.repeat(r.stars) }}{{ '☆'.repeat(5 - r.stars) }}
        </span>
        <small>{{ dateTime(r.createdAt) }}</small>
      </header>
      <p class="preserve">{{ r.content }}</p>
    </article>
    <nav
      v-if="data && data.total > data.pageSize"
      class="pagination"
      aria-label="评价分页"
    >
      <button :disabled="page <= 1 || loading" @click="turn(-1)">
        ← 上一页
      </button>
      <span>{{ page }}</span>
      <button
        :disabled="page * data.pageSize >= data.total || loading"
        @click="turn(1)"
      >
        下一页 →
      </button>
    </nav>
  </section>
</template>
