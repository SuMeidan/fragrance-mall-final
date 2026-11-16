<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { errorText, query } from '@shared/api'
import type { CategoryTree, Family, Page, Product } from '@shared/models'
import { api, catalog } from '../state'
import { publicProducts } from '../public-catalog'
import ProductCard from '../components/ProductCard.vue'
import FacetSelect from '../components/FacetSelect.vue'
import State from '../components/State.vue'
import { Search, X } from '@lucide/vue'
const route = useRoute(),
  router = useRouter(),
  categories = ref<CategoryTree[]>([]),
  families = ref<Family[]>([]),
  data = ref<Page<Product>>(),
  candidates = ref<Product[]>([]),
  error = ref(''),
  loading = ref(false),
  search = ref('')
const categoryId = computed(() => Number(route.query.categoryId) || null),
  root = computed(() =>
    categories.value.find(
      (c) =>
        c.id === categoryId.value ||
        c.children.some((x) => x.id === categoryId.value),
    ),
  ),
  leaf = computed(() =>
    root.value?.children.find((c) => c.id === categoryId.value),
  )
const scoped = computed(() =>
  leaf.value
    ? candidates.value.filter((p) => p.categoryId === leaf.value!.id)
    : candidates.value,
)
const availableTypes = computed(
    () => new Set(candidates.value.map((p) => p.categoryId)),
  ),
  availableFamilies = computed(
    () =>
      new Set(scoped.value.map((p) => p.familyId).filter((x) => x !== null)),
  )
const typeOptions = computed(() => [
  { value: root.value?.id || '', label: '全部类型' },
  ...(root.value ? [root.value] : categories.value)
    .flatMap((c) => c.children)
    .filter((c) => availableTypes.value.has(c.id))
    .map((c) => ({ value: c.id, label: c.name })),
])
const familyOptions = computed(() => [
  { value: '', label: '全部香型' },
  ...families.value
    .filter((f) => availableFamilies.value.has(f.id))
    .map((f) => ({ value: String(f.id), label: f.name })),
])
const familyDisabled = computed(() => availableFamilies.value.size < 2),
  typeDisabled = computed(() => availableTypes.value.size < 2),
  sortDisabled = computed(
    () =>
      new Set(
        scoped.value
          .filter(
            (p) =>
              !route.query.familyId ||
              p.familyId === Number(route.query.familyId),
          )
          .map((p) => p.priceCents),
      ).size < 2,
  )
const emptyMessage = computed(() =>
  route.query.keyword
    ? `未找到与“${String(route.query.keyword)}”相关的作品，可以换个关键词或清空条件。`
    : '当前分类与香型下暂无作品，可以调整条件或查看全部作品。',
)
let generation = 0
async function load() {
  const current = ++generation
  loading.value = true
  error.value = ''
  search.value = String(route.query.keyword || '')
  try {
    const meta = await catalog()
    if (current !== generation) return
    categories.value = meta.categories
    families.value = meta.families
    const inventory = await publicProducts({
      ...(root.value ? { categoryId: root.value.id } : {}),
      ...(search.value ? { keyword: search.value } : {}),
    })
    if (current !== generation) return
    candidates.value = inventory
    const old = Number(route.query.familyId)
    if (old && (familyDisabled.value || !availableFamilies.value.has(old))) {
      await router.replace({
        query: { ...route.query, familyId: undefined, page: undefined },
      })
      return
    }
    const result = await api.request<Page<Product>>(
      '/catalog/products' +
        query({
          keyword: search.value,
          categoryId: categoryId.value,
          familyId: old || null,
          unscented: route.query.unscented === 'true' ? 'true' : undefined,
          sort: String(route.query.sort || 'newest'),
          page: Number(route.query.page) || 1,
        }),
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
function filter(key: string, value: string | number | null) {
  router.push({
    query: {
      ...route.query,
      [key]: value || undefined,
      ...(['categoryId', 'keyword'].includes(key)
        ? { familyId: undefined, unscented: undefined }
        : {}),
      ...(key === 'familyId' ? { unscented: undefined } : {}),
      page: key === 'page' ? value : undefined,
    },
  })
}
function clearSearch() {
  search.value = ''
  filter('keyword', null)
}
function clearFilters() {
  router.push({ path: '/catalog' })
}
watch(() => route.fullPath, load, { immediate: true })
</script>
<template>
  <div class="wrap page catalog-page">
    <div class="catalog-heading">
      <div>
        <h1>{{ leaf?.name || root?.name || '全部商品' }}</h1>
        <p aria-live="polite">{{ data?.total || 0 }} 件商品</p>
      </div>
      <form
        class="search-field catalog-search"
        role="search"
        @submit.prevent="filter('keyword', search)"
      >
        <Search class="search-leading" :size="16" aria-hidden="true" />
        <input
          id="catalog-search"
          v-model="search"
          aria-label="搜索商品"
          placeholder="搜索商品"
          maxlength="100"
        />
        <button
          v-if="search"
          type="button"
          aria-label="清空搜索关键词"
          @click="clearSearch"
        >
          <X :size="16" />
        </button>
        <button type="submit" aria-label="搜索"><Search :size="18" /></button>
      </form>
    </div>
    <nav class="category-tabs" aria-label="商品大类">
      <button
        :class="{ active: !categoryId }"
        @click="filter('categoryId', null)"
      >
        全部
      </button>
      <button
        v-for="c in categories"
        :key="c.id"
        :class="{ active: root?.id === c.id }"
        @click="filter('categoryId', c.id)"
      >
        {{ c.name }}
      </button>
    </nav>
    <div class="catalog-toolbar">
      <FacetSelect
        label="商品类型"
        :model-value="leaf?.id || root?.id || ''"
        :options="typeOptions"
        :disabled="loading || typeDisabled"
        :disabled-label="
          typeDisabled ? typeOptions[1]?.label || '暂无可选类型' : ''
        "
        @update:model-value="filter('categoryId', $event)"
      />
      <FacetSelect
        label="香型"
        :model-value="String(route.query.familyId || '')"
        :options="familyOptions"
        :disabled="loading || familyDisabled"
        :disabled-label="
          familyDisabled
            ? availableFamilies.size === 0
              ? '不适用'
              : familyOptions[1]?.label
            : ''
        "
        @update:model-value="filter('familyId', $event)"
      />
      <FacetSelect
        label="排序"
        :model-value="String(route.query.sort || 'newest')"
        :options="[
          { value: 'newest', label: '最新作品' },
          { value: 'price_asc', label: '价格从低到高' },
          { value: 'price_desc', label: '价格从高到低' },
        ]"
        :disabled="loading || sortDisabled"
        @update:model-value="filter('sort', $event)"
      />
      <button
        class="catalog-reset"
        :disabled="!Object.keys(route.query).length"
        @click="clearFilters"
      >
        清除筛选
      </button>
    </div>
    <State
      :loading="loading"
      :error="error"
      :empty="!loading && !error && !data?.items.length ? emptyMessage : ''"
      @retry="load"
    >
      <button
        v-if="!loading && !error && !data?.items.length"
        class="btn secondary"
        @click="clearFilters"
      >
        清空条件，查看全部作品
      </button>
    </State>
    <div v-if="!loading && !error" class="product-grid">
      <ProductCard
        v-for="(p, i) in data?.items"
        :key="p.id"
        :product="p"
        :index="i"
      />
    </div>
    <nav
      v-if="data && data.total > data.pageSize"
      class="pagination"
      aria-label="商品分页"
    >
      <button
        :disabled="data.page <= 1 || loading"
        @click="filter('page', data.page - 1)"
      >
        ← 上一页
      </button>
      <span>{{ data.page }} / {{ Math.ceil(data.total / data.pageSize) }}</span>
      <button
        :disabled="data.page * data.pageSize >= data.total || loading"
        @click="filter('page', data.page + 1)"
      >
        下一页 →
      </button>
    </nav>
  </div>
</template>
