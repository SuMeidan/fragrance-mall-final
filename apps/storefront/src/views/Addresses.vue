<script setup lang="ts">
import AddressBook from '../components/AddressBook.vue'
import AccountNav from '../components/AccountNav.vue'
</script>
<template>
  <div class="wrap page">
    <div class="page-heading">
      <p class="eyebrow">YOUR ADDRESSES</p>
      <h1>把喜欢，送到你身边。</h1>
      <p>管理收货信息，让每一份心意都有归处。</p>
    </div>
    <div class="account-layout">
      <AccountNav />
      <AddressBook />
    </div>
  </div>
</template>
