<template>
  <aside class="account-nav" aria-label="账户导航">
    <span>YOUR SCENT</span>
    <RouterLink to="/account">
      账户概览
      <span aria-hidden="true">↗</span>
    </RouterLink>
    <RouterLink to="/orders">
      我的订单
      <span aria-hidden="true">↗</span>
    </RouterLink>
    <RouterLink to="/addresses">
      收货地址
      <span aria-hidden="true">↗</span>
    </RouterLink>
    <RouterLink to="/cart">
      购物袋
      <span aria-hidden="true">↗</span>
    </RouterLink>
  </aside>
</template>
