<script setup lang="ts">
import {
  computed,
  nextTick,
  onMounted,
  onUnmounted,
  ref,
  watch,
  useId,
} from 'vue'
const props = defineProps<{
  label: string
  modelValue: string | number
  options: { value: string | number; label: string }[]
  disabled?: boolean
  disabledLabel?: string
}>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const open = ref(false),
  active = ref(0),
  trigger = ref<HTMLButtonElement>(),
  panel = ref<HTMLElement>(),
  position = ref({}),
  id = useId()
const caption = computed(() =>
  props.disabledLabel && props.disabled
    ? props.disabledLabel
    : props.options.find((o) => String(o.value) === String(props.modelValue))
        ?.label ||
      props.options[0]?.label ||
      '暂无可选项',
)
function close() {
  open.value = false
}
function place() {
  if (!trigger.value || !open.value) return
  const r = trigger.value.getBoundingClientRect(),
    width = Math.min(Math.max(r.width, 260), innerWidth - 24),
    below = innerHeight - r.bottom - 16,
    above = r.top - 16,
    up = below < 180 && above > below
  if (r.bottom < 0 || r.top > innerHeight) return close()
  position.value = {
    width: width + 'px',
    maxHeight: Math.max(100, Math.min(360, up ? above : below)) + 'px',
    left: Math.max(12, Math.min(r.left, innerWidth - width - 12)) + 'px',
    ...(up
      ? { bottom: innerHeight - r.top + 8 + 'px' }
      : { top: r.bottom + 8 + 'px' }),
  }
}
function highlight(i: number) {
  active.value = Math.max(0, Math.min(i, props.options.length - 1))
  nextTick(() => {
    const option = panel.value?.children[active.value] as
        HTMLElement | undefined,
      menu = panel.value
    if (!menu || !option) return
    if (option.offsetTop < menu.scrollTop) menu.scrollTop = option.offsetTop
    if (
      option.offsetTop + option.offsetHeight >
      menu.scrollTop + menu.clientHeight
    )
      menu.scrollTop =
        option.offsetTop + option.offsetHeight - menu.clientHeight
  })
}
function show() {
  if (props.disabled) return
  open.value = true
  place()
  highlight(
    Math.max(
      0,
      props.options.findIndex(
        (o) => String(o.value) === String(props.modelValue),
      ),
    ),
  )
}
function choose(i: number) {
  const option = props.options[i]
  if (option) emit('update:modelValue', String(option.value))
  close()
  trigger.value?.focus()
}
function key(event: KeyboardEvent) {
  if (event.key === 'Tab') return close()
  if (
    !['ArrowDown', 'ArrowUp', 'Home', 'End', 'Enter', ' ', 'Escape'].includes(
      event.key,
    ) ||
    props.disabled
  )
    return
  event.preventDefault()
  if (event.key === 'Escape') return close()
  if (!open.value) return show()
  if (['Enter', ' '].includes(event.key)) return choose(active.value)
  highlight(
    event.key === 'Home'
      ? 0
      : event.key === 'End'
        ? props.options.length - 1
        : (active.value +
            (event.key === 'ArrowUp' ? -1 : 1) +
            props.options.length) %
          props.options.length,
  )
}
function outside(event: Event) {
  if (
    !trigger.value?.contains(event.target as Node) &&
    !panel.value?.contains(event.target as Node)
  )
    close()
}
watch(() => [props.disabled, props.modelValue, props.options], close)
onMounted(() => {
  document.addEventListener('pointerdown', outside)
  window.addEventListener('resize', place)
  window.addEventListener('scroll', place, true)
})
onUnmounted(() => {
  document.removeEventListener('pointerdown', outside)
  window.removeEventListener('resize', place)
  window.removeEventListener('scroll', place, true)
})
</script>
<template>
  <div class="facet-field">
    <span>{{ label }}</span>
    <button
      ref="trigger"
      class="facet-trigger"
      type="button"
      role="combobox"
      :aria-label="label"
      aria-haspopup="listbox"
      :aria-expanded="open"
      :aria-controls="id"
      :aria-activedescendant="open ? `${id}-${active}` : undefined"
      :disabled="disabled"
      @click="open ? close() : show()"
      @keydown="key"
    >
      <span>{{ caption }}</span>
      <svg v-if="!disabled" viewBox="0 0 16 16" aria-hidden="true">
        <path
          d="m4 6 4 4 4-4"
          fill="none"
          stroke="currentColor"
          stroke-width="1.2"
        />
      </svg>
    </button>
  </div>
  <Teleport to="body">
    <div
      v-if="open"
      :id="id"
      ref="panel"
      class="facet-popover"
      :style="position"
      role="listbox"
      :aria-label="label"
    >
      <div
        v-for="(option, i) in options"
        :id="`${id}-${i}`"
        :key="option.value"
        role="option"
        :aria-selected="String(modelValue) === String(option.value)"
        :class="{ 'keyboard-active': i === active }"
        @pointermove="active = i"
        @pointerdown.prevent="choose(i)"
      >
        {{ option.label }}
        <span
          v-if="String(modelValue) === String(option.value)"
          aria-hidden="true"
        >
          ✓
        </span>
      </div>
    </div>
  </Teleport>
</template>
