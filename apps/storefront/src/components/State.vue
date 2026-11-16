<script setup lang="ts">
defineProps<{ loading?: boolean; error?: string; empty?: string }>()
defineEmits<{ retry: [] }>()
</script>
<template>
  <div
    v-if="loading || error || empty"
    class="state-panel"
    :role="error ? 'alert' : 'status'"
  >
    <span v-if="loading" class="eyebrow">SCENT · LOADING</span>
    <p>{{ loading ? '正在载入…' : error || empty }}</p>
    <button v-if="error" class="text-link" @click="$emit('retry')">
      重新载入
    </button>
    <slot />
  </div>
</template>
