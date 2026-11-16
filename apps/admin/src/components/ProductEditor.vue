<!-- Two-level category and four business forms replace the upstream three-level/WangEditor/MD5 design.
     Workflow adapted from newbee-ltd AddGood.vue, GPL-3.0-or-later. -->
<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type {
  Category,
  Family,
  Page,
  Product,
  ProductDetail,
  ProductInput,
} from '@shared/models'
import { query } from '@shared/api'
import { asset } from '@shared/format'
import { api, fail } from '../state'
const props = defineProps<{ id: number | null }>(),
  emit = defineEmits<{ close: []; saved: [] }>()
const categories = ref<Category[]>([]),
  families = ref<Family[]>([]),
  related = ref<Product[]>([]),
  rootId = ref<number>(),
  price = ref(''),
  loading = ref(true),
  saving = ref(false)
const uploading = ref(false),
  currentStock = ref(0)
function rootChanged() {
  form.categoryId = 0
  form.familyId = null
}
function categoryChanged(value: number) {
  form.categoryId = value
  if (!needsScent.value) form.familyId = null
}
function imageLabel(slot: 'scentImagePath' | 'sceneImagePath') {
  return slot === 'sceneImagePath'
    ? '场景横图'
    : inputView.value === 'TOOL'
      ? '器具细节图'
      : '香调意象图'
}
async function upload(
  event: Event,
  slot: 'main' | 'scentImagePath' | 'sceneImagePath' = 'main',
) {
  const field = event.target as HTMLInputElement,
    file = field.files?.[0]
  if (!file) return
  if (
    !['image/png', 'image/jpeg'].includes(file.type) ||
    file.size > 5 * 1024 * 1024
  ) {
    ElMessage.error('请选择5MiB以内PNG或JPEG图片')
    field.value = ''
    return
  }
  uploading.value = true
  try {
    const body = new FormData()
    body.append('file', file)
    const path = (
      await api.request<{ imagePath: string }>('/admin/media', {
        method: 'POST',
        body,
      })
    ).imagePath
    if (slot === 'main') form.imagePath = path
    else form.extraImages![slot] = path
    ElMessage.success(
      `${slot === 'main' ? '主图' : imageLabel(slot)}已上传，保存商品后生效`,
    )
  } catch (error) {
    fail(error)
  } finally {
    uploading.value = false
    field.value = ''
  }
}
const form = reactive<ProductInput>({
  categoryId: 0,
  familyId: null,
  name: '',
  subtitle: '',
  spec: '',
  unit: '件',
  priceCents: 1,
  initialStock: 0,
  active: true,
  version: null,
  imagePath: '/assets/candle.png',
  description: '',
  usageNotes: '',
  material: '',
  powerInfo: '',
  contents: '',
  compatibleIds: [],
  extraImages: { scentImagePath: null, sceneImagePath: null },
})
const leaves = computed(() =>
    categories.value.filter((c) => c.parentId === rootId.value),
  ),
  inputView = computed(
    () => categories.value.find((c) => c.id === form.categoryId)?.inputView,
  ),
  needsScent = computed(
    () => inputView.value === 'SCENT' || inputView.value === 'REFILL',
  )
const descriptionHint = computed(() =>
  inputView.value === 'TOOL'
    ? '产品定位与是否有香\n材质与固定规格\n适用器具或耗材\n不适合的用途与使用限制'
    : inputView.value === 'GIFT'
      ? '礼盒整体风格与香型\n甜度、气味浓淡\n适合与不适合的偏好\n固定包含物与使用限制'
      : '气味特点与香型\n甜度与气味浓淡\n适合与不适合的偏好\n使用方式与明确适配关系',
)
const shapeOptions = computed(() =>
  categories.value
    .filter((c) => c.parentId && /^P(0[3-9]|1[0-8])$/.test(c.code))
    .map((c) => ({
      label: `${c.name}（形态示意）`,
      path: `/assets/types/${c.code.toLowerCase()}.svg`,
    })),
)
async function searchRelated(keyword: string) {
  try {
    const result = await api.request<Page<Product>>(
      '/admin/products' + query({ keyword, pageSize: 40 }),
    )
    const chosen = related.value.filter((p) =>
      form.compatibleIds.includes(p.id),
    )
    related.value = [
      ...new Map(
        [...chosen, ...result.items.filter((p) => p.id !== props.id)].map(
          (p) => [p.id, p],
        ),
      ).values(),
    ]
  } catch (e) {
    fail(e)
  }
}
async function save() {
  if (saving.value || uploading.value) return
  if (inputView.value === 'TOOL') form.familyId = null
  if (!/^\d{1,7}(\.\d{1,2})?$/.test(price.value)) {
    ElMessage.error('售价需为最多两位小数的正数')
    return
  }
  const [yuan, fraction = ''] = price.value.split('.')
  form.priceCents = Number(yuan) * 100 + Number(fraction.padEnd(2, '0'))
  saving.value = true
  try {
    await api.request('/admin/products' + (props.id ? `/${props.id}` : ''), {
      method: props.id ? 'PUT' : 'POST',
      body: JSON.stringify(form),
    })
    ElMessage.success('商品已保存')
    emit('saved')
  } catch (e) {
    fail(e)
  } finally {
    saving.value = false
  }
}
onMounted(async () => {
  try {
    ;[categories.value, families.value] = await Promise.all([
      api.request<Category[]>('/admin/categories'),
      api.request<Family[]>('/catalog/families', {}, false),
    ])
    if (props.id) {
      const data = await api.request<ProductDetail>(
        `/admin/products/${props.id}`,
      )
      Object.assign(
        form,
        Object.fromEntries(
          Object.keys(form)
            .filter((k) => k in data.product)
            .map((k) => [k, data.product[k as keyof Product]]),
        ),
        {
          initialStock: null,
          compatibleIds: data.compatibleProducts.map((p) => p.id),
          extraImages: {
            scentImagePath: data.product.scentImagePath,
            sceneImagePath: data.product.sceneImagePath,
          },
        },
      )
      price.value = (form.priceCents / 100).toFixed(2)
      currentStock.value = data.product.stock
      if (inputView.value === 'TOOL') form.familyId = null
      rootId.value =
        categories.value.find((c) => c.id === form.categoryId)?.parentId ||
        undefined
      related.value = data.compatibleProducts
    }
  } catch (e) {
    fail(e)
    emit('close')
  } finally {
    loading.value = false
  }
})
</script>
<template>
  <el-dialog
    :model-value="true"
    :title="id ? '编辑商品' : '新增商品'"
    class="product-editor-dialog"
    modal-class="editor-overlay"
    :show-close="false"
    :close-on-click-modal="false"
    @close="emit('close')"
  >
    <template #header="{ titleId }">
      <div class="workspace-heading">
        <div>
          <h1 :id="titleId">{{ id ? '编辑商品' : '新增商品' }}</h1>
          <p>把作品的细节，清楚地呈现给买家。</p>
        </div>
        <div class="editor-heading-actions">
          <el-button :disabled="saving || uploading" @click="emit('close')">
            取消
          </el-button>
          <el-button
            type="primary"
            native-type="submit"
            form="product-editor-form"
            :disabled="!form.categoryId || loading || uploading"
            :loading="saving"
          >
            保存商品
          </el-button>
        </div>
      </div>
    </template>
    <p v-if="loading" role="status">正在加载商品资料…</p>
    <form
      v-else
      id="product-editor-form"
      class="product-form editor-layout"
      @submit.prevent="save"
    >
      <div class="editor-main">
        <section class="editor-panel">
          <h2>基本信息</h2>
          <div class="form-grid">
            <label for="product-rootId">
              大类
              <el-select
                id="product-rootId"
                v-model="rootId"
                aria-label="大类"
                placeholder="选择大类"
                @change="rootChanged"
              >
                <el-option
                  v-for="c in categories.filter((c) => !c.parentId)"
                  :key="c.id"
                  :value="c.id"
                  :label="c.name"
                />
              </el-select>
            </label>
            <label for="product-categoryId">
              商品类型
              <el-select
                id="product-categoryId"
                aria-label="商品类型"
                :model-value="form.categoryId || undefined"
                :disabled="!rootId"
                @update:model-value="categoryChanged"
                placeholder="选择类型"
              >
                <el-option
                  v-for="c in leaves"
                  :key="c.id"
                  :value="c.id"
                  :label="c.name"
                />
              </el-select>
            </label>
            <label for="product-name">
              商品名称
              <el-input
                id="product-name"
                v-model="form.name"
                required
                maxlength="100"
              />
            </label>
            <label for="product-subtitle">
              简短描述
              <el-input
                id="product-subtitle"
                v-model="form.subtitle"
                maxlength="160"
              />
            </label>
            <label for="product-familyId">
              香型
              <el-select
                id="product-familyId"
                aria-label="香型"
                v-model="form.familyId"
                :disabled="!needsScent"
                :clearable="needsScent"
                :placeholder="
                  inputView === 'TOOL'
                    ? '不适用'
                    : inputView === 'GIFT'
                      ? '按固定内容展示'
                      : '选择香型'
                "
                @clear="form.familyId = null"
              >
                <el-option
                  v-for="family in families"
                  :key="family.id"
                  :label="family.name"
                  :value="family.id"
                />
              </el-select>
              <small v-if="inputView === 'TOOL'" class="field-hint">
                器具不设置香型，也不参与香型筛选。
              </small>
              <small v-else-if="inputView === 'GIFT'" class="field-hint">
                按固定礼盒内容展示，无需另外选择。
              </small>
            </label>
            <label for="product-spec">
              固定规格
              <el-input
                id="product-spec"
                v-model="form.spec"
                required
                maxlength="80"
                placeholder="例如 180g / 琥珀玻璃杯"
              />
            </label>
            <label for="product-unit">
              销售单位
              <el-input
                id="product-unit"
                v-model="form.unit"
                required
                maxlength="16"
              />
            </label>
            <label for="product-price">
              售价（元）
              <el-input
                id="product-price"
                v-model="price"
                required
                inputmode="decimal"
                placeholder="129.00"
              />
            </label>
            <label v-if="!id" for="product-initialStock">
              初始库存
              <el-input-number
                id="product-initialStock"
                v-model="form.initialStock"
                :min="0"
                :max="1000000"
                :precision="0"
              />
            </label>
          </div>
        </section>
        <section class="editor-panel">
          <h2>作品描述</h2>
          <label for="product-description">
            商品说明
            <el-input
              id="product-description"
              v-model="form.description"
              type="textarea"
              :rows="4"
              required
              maxlength="4000"
              show-word-limit
              :placeholder="descriptionHint"
            />
            <small class="field-hint">
              写清气味或材质、适用范围与限制，香氛顾问会依据这些资料推荐。
            </small>
          </label>
          <div v-if="inputView === 'TOOL'" class="form-grid">
            <label for="product-material">
              材质 / 尺寸（必填）
              <el-input
                id="product-material"
                v-model="form.material"
                required
                maxlength="200"
              />
            </label>
            <label for="product-powerInfo">
              供电说明
              <el-input
                id="product-powerInfo"
                v-model="form.powerInfo"
                maxlength="200"
              />
            </label>
          </div>
          <label v-if="inputView === 'GIFT'" for="product-contents">
            固定包含物（必填）
            <el-input
              id="product-contents"
              v-model="form.contents"
              required
              type="textarea"
              :rows="2"
              maxlength="1500"
            />
          </label>
          <label for="product-usageNotes">
            使用说明{{ needsScent ? '（必填）' : '' }}
            <el-input
              id="product-usageNotes"
              v-model="form.usageNotes"
              type="textarea"
              :rows="2"
              :required="needsScent"
              maxlength="1500"
            />
          </label>
          <label for="product-compatibleIds">
            适配商品（人工指定，最多20项）
            <el-select
              id="product-compatibleIds"
              v-model="form.compatibleIds"
              multiple
              filterable
              remote
              :remote-method="searchRelated"
              :multiple-limit="20"
              placeholder="输入名称查找"
            >
              <el-option
                v-for="p in related"
                :key="p.id"
                :value="p.id"
                :label="`${p.id} · ${p.name}`"
              />
            </el-select>
          </label>
        </section>
      </div>
      <div class="editor-assets">
        <section class="editor-panel">
          <h2>商品影像</h2>
          <img
            class="editor-main-image"
            :src="asset(form.imagePath)"
            alt="商品主图预览"
          />
          <div class="media-primary-actions">
            <label for="product-upload" class="media-upload">
              更换主图
              <input
                id="product-upload"
                type="file"
                accept="image/png,image/jpeg"
                :disabled="uploading || saving"
                @change="upload($event)"
              />
            </label>
            <a :href="asset(form.imagePath)" target="_blank" rel="noopener">
              查看原图
            </a>
          </div>
          <div v-if="form.extraImages" class="media-secondary">
            <div
              v-for="slot in ['scentImagePath', 'sceneImagePath'] as const"
              :key="slot"
              class="media-tile"
            >
              <label
                :for="`product-${slot}`"
                class="media-upload media-thumbnail"
              >
                <img
                  v-if="form.extraImages[slot]"
                  :src="asset(form.extraImages[slot]!)"
                  :alt="`${imageLabel(slot)}预览`"
                />
                <span v-else class="media-empty" aria-hidden="true">影像</span>
                <span>
                  {{ imageLabel(slot) }}
                  <small>
                    {{ form.extraImages[slot] ? '点击更换' : '点击上传' }}
                  </small>
                </span>
                <input
                  :id="`product-${slot}`"
                  type="file"
                  :aria-label="`上传${imageLabel(slot)}`"
                  accept="image/png,image/jpeg"
                  :disabled="uploading || saving"
                  @change="upload($event, slot)"
                />
              </label>
              <el-button
                v-if="form.extraImages[slot]"
                link
                :disabled="uploading || saving"
                @click="form.extraImages[slot] = null"
              >
                清除{{ imageLabel(slot) }}
              </el-button>
            </div>
          </div>
          <details class="image-materials" :open="!id">
            <summary>选择现有素材</summary>
            <label for="product-imagePath" class="image-path-select">
              主图素材路径
              <el-select
                id="product-imagePath"
                v-model="form.imagePath"
                filterable
                allow-create
                default-first-option
                placeholder="选择素材路径"
              >
                <el-option label="蜡烛照片" value="/assets/candle.png" />
                <el-option label="藤条扩香照片" value="/assets/diffuser.png" />
                <el-option
                  v-for="option in shapeOptions"
                  :key="option.path"
                  :label="option.label"
                  :value="option.path"
                />
              </el-select>
            </label>
          </details>
          <p v-if="uploading" role="status" class="admin-note">正在处理图片…</p>
          <p class="field-hint">JPG / PNG · ≤ 5 MiB · 保存后生效</p>
        </section>
        <section class="editor-panel">
          <h2>上架与库存</h2>
          <div class="editor-switch">
            <label for="product-active">上架状态</label>
            <el-switch
              id="product-active"
              v-model="form.active"
              aria-label="上架状态"
            />
          </div>
          <template v-if="id">
            <p class="editor-stock-value">
              {{ currentStock }}
              <span>当前库存</span>
            </p>
            <p class="field-hint">库存变更请使用独立的「调整库存」。</p>
          </template>
          <p v-else class="field-hint">初始库存仅在创建商品时设置。</p>
        </section>
      </div>
    </form>
  </el-dialog>
</template>
