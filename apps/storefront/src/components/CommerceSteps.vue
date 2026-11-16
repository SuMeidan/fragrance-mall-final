<script setup lang="ts">
defineProps<{ active: number }>()
</script>
<template>
  <ol class="commerce-steps" aria-label="购买进度">
    <li
      v-for="(label, i) in ['购物袋', '确认订单', '付款', '完成']"
      :key="label"
      :aria-current="active === i ? 'step' : undefined"
      :class="{ done: i < active }"
    >
      <span>{{ String(i + 1).padStart(2, '0') }}</span>
      {{ label }}
    </li>
  </ol>
</template>
