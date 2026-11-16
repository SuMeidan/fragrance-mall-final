<script setup lang="ts">
defineProps<{ name: string }>()
</script>
<template>
  <svg
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.4"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <template v-if="name === '/products'">
      <rect x="4" y="4" width="16" height="16" rx="2" />
      <path d="M4 10h16M10 4v16" />
    </template>
    <path v-else-if="name === '/categories'" d="M4 6h16M4 12h16M4 18h16" />
    <template v-else-if="name === '/orders'">
      <rect x="5" y="4" width="14" height="17" rx="2" />
      <path d="M9 4V3h6v1M9 10h6M9 15h6" />
    </template>
    <path
      v-else-if="name === '/after-sales'"
      d="m8 5-4 4 4 4M4 9h10a6 6 0 0 1 0 12"
    />
    <template v-else-if="name === '/finance'">
      <rect x="4" y="4" width="16" height="16" rx="2" />
      <path d="M9 4v16M14 8h2M14 12h2M14 16h2" />
    </template>
    <template v-else-if="name === '/accounts'">
      <circle cx="12" cy="8" r="3.5" />
      <path d="M5 21v-2a7 7 0 0 1 14 0v2" />
    </template>
    <template v-else>
      <path d="M8 6h12M8 12h12M8 18h12M4 6h.01M4 12h.01M4 18h.01" />
    </template>
  </svg>
</template>
