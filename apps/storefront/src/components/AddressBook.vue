<script setup lang="ts">
import { nextTick, onMounted, reactive, ref, useId } from 'vue'
import type { Address, AddressInput } from '@shared/models'
import { errorText } from '@shared/api'
import { api } from '../state'
const props = defineProps<{ selectable?: boolean; modelValue?: number }>()
const emit = defineEmits<{ 'update:modelValue': [id: number] }>()
const addresses = ref<Address[]>([]),
  loading = ref(false),
  error = ref(''),
  editing = ref<number | null | undefined>(),
  saving = ref(false)
const editor = ref<HTMLDialogElement>(),
  editorTitle = useId()
function closeEditor() {
  if (saving.value) return
  editing.value = undefined
  editor.value?.close()
}
const form = reactive<AddressInput>({
  receiver: '',
  phone: '',
  region: '',
  detail: '',
  isDefault: false,
})
async function load() {
  loading.value = true
  error.value = ''
  try {
    addresses.value = await api.request<Address[]>('/addresses')
    if (
      props.selectable &&
      !addresses.value.some((a) => a.id === props.modelValue)
    ) {
      const first =
        addresses.value.find((a) => a.isDefault) || addresses.value[0]
      if (first) emit('update:modelValue', first.id)
    }
  } catch (e) {
    error.value = errorText(e)
  } finally {
    loading.value = false
  }
}
function edit(a?: Address) {
  editing.value = a?.id ?? null
  Object.assign(
    form,
    a
      ? {
          receiver: a.receiver,
          phone: a.phone,
          region: a.region,
          detail: a.detail,
          isDefault: a.isDefault,
        }
      : { receiver: '', phone: '', region: '', detail: '', isDefault: false },
  )
  nextTick(() => editor.value?.showModal())
}
async function save() {
  saving.value = true
  error.value = ''
  try {
    const a = await api.request<Address>(
      '/addresses' + (editing.value ? `/${editing.value}` : ''),
      { method: editing.value ? 'PUT' : 'POST', body: JSON.stringify(form) },
    )
    editing.value = undefined
    editor.value?.close()
    if (props.selectable) emit('update:modelValue', a.id)
    await load()
  } catch (e) {
    error.value = errorText(e)
  } finally {
    saving.value = false
  }
}
async function remove(a: Address) {
  if (!window.confirm(`删除 ${a.receiver} 的这个地址？`)) return
  saving.value = true
  try {
    await api.request(`/addresses/${a.id}`, { method: 'DELETE' })
    await load()
  } catch (e) {
    error.value = errorText(e)
  } finally {
    saving.value = false
  }
}
onMounted(load)
</script>
<template>
  <div class="address-book">
    <div class="section-head compact">
      <h2>收货地址</h2>
      <button class="text-link" @click="edit()">＋ 添加地址</button>
    </div>
    <p v-if="loading" role="status">正在读取地址…</p>
    <p v-if="error" class="form-error" role="alert">
      {{ error }}
      <button class="text-link" @click="load">重试</button>
    </p>
    <p v-if="!loading && !addresses.length" class="quiet">
      添加一个收货地址，便于把香气送到你身边。
    </p>
    <div class="address-list">
      <article
        v-for="a in addresses"
        :key="a.id"
        class="address-item"
        :class="{ selected: selectable && modelValue === a.id }"
      >
        <label v-if="selectable" class="address-choice">
          <input
            type="radio"
            name="address"
            :value="a.id"
            :checked="modelValue === a.id"
            @change="emit('update:modelValue', a.id)"
          />
          <span>
            <strong>{{ a.receiver }} · {{ a.phone }}</strong>
            <small>{{ a.region }} {{ a.detail }}</small>
          </span>
        </label>
        <div v-else>
          <strong>{{ a.receiver }} · {{ a.phone }}</strong>
          <p>{{ a.region }} {{ a.detail }}</p>
        </div>
        <div class="address-actions">
          <span v-if="a.isDefault" class="quiet">默认</span>
          <button class="text-link" @click="edit(a)">编辑</button>
          <button class="text-link" :disabled="saving" @click="remove(a)">
            删除
          </button>
        </div>
      </article>
    </div>
    <dialog
      ref="editor"
      class="address-dialog"
      :aria-labelledby="editorTitle"
      @cancel="
        (event) => {
          if (saving) event.preventDefault()
          else editing = undefined
        }
      "
    >
      <form
        v-if="editing !== undefined"
        class="address-form"
        @submit.prevent="save"
      >
        <h3 :id="editorTitle">{{ editing ? '编辑地址' : '新建地址' }}</h3>
        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
        <div class="two-fields">
          <label>
            收货人
            <input
              v-model="form.receiver"
              required
              maxlength="32"
              autocomplete="name"
            />
          </label>
          <label>
            联系电话
            <input
              v-model="form.phone"
              required
              pattern="[0-9+\- ]{6,20}"
              maxlength="20"
              type="tel"
              autocomplete="tel"
            />
          </label>
        </div>
        <label>
          省市区
          <input
            v-model="form.region"
            required
            maxlength="120"
            autocomplete="address-level2"
            placeholder="省 / 市 / 区"
          />
        </label>
        <label>
          详细地址
          <input
            v-model="form.detail"
            required
            maxlength="200"
            autocomplete="street-address"
            placeholder="街道、门牌等"
          />
        </label>
        <label class="checkbox">
          <input v-model="form.isDefault" type="checkbox" />
          设为默认地址
        </label>
        <div class="actions">
          <button class="btn" :disabled="saving">
            {{ saving ? '保存中…' : '保存地址' }}
          </button>
          <button
            type="button"
            class="btn secondary"
            :disabled="saving"
            @click="closeEditor"
          >
            取消
          </button>
        </div>
      </form>
    </dialog>
  </div>
</template>
