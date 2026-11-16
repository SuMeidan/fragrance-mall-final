import { createRouter, createWebHistory } from 'vue-router'
import { api } from './state'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  scrollBehavior: (to, _from, saved) =>
    saved || (to.hash ? { el: to.hash, top: 100 } : { top: 0 }),
  routes: [
    { path: '/', component: () => import('./views/Home.vue') },
    {
      path: '/catalog',
      component: () => import('./views/Catalog.vue'),
      meta: { title: '商品目录' },
    },
    {
      path: '/products/:id',
      component: () => import('./views/Product.vue'),
      meta: { title: '商品详情' },
    },
    {
      path: '/login',
      component: () => import('./views/Auth.vue'),
      meta: { title: '登录' },
    },
    {
      path: '/register',
      component: () => import('./views/Auth.vue'),
      meta: { title: '创建账号' },
    },
    {
      path: '/cart',
      component: () => import('./views/Cart.vue'),
      meta: { auth: true, title: '购物袋' },
    },
    {
      path: '/checkout',
      component: () => import('./views/Checkout.vue'),
      meta: { auth: true, title: '确认订单' },
    },
    {
      path: '/payment/:id',
      component: () => import('./views/Payment.vue'),
      meta: { auth: true, title: '付款与订单状态' },
    },
    {
      path: '/orders',
      component: () => import('./views/Orders.vue'),
      meta: { auth: true, title: '我的订单' },
    },
    {
      path: '/orders/:id',
      component: () => import('./views/Order.vue'),
      meta: { auth: true, title: '订单详情' },
    },
    {
      path: '/addresses',
      component: () => import('./views/Addresses.vue'),
      meta: { auth: true, title: '收货地址' },
    },
    {
      path: '/account',
      component: () => import('./views/Account.vue'),
      meta: { auth: true, title: '我的账户' },
    },
    {
      path: '/:pathMatch(.*)*',
      component: () => import('./views/NotFound.vue'),
      meta: { title: '页面未找到' },
    },
  ],
})
router.beforeEach(async (to) => {
  if (!to.meta.auth) return
  if (api.token.value && !api.user.value) {
    try {
      await api.me()
    } catch {
      /* Login offers a fresh session. */
    }
  }
  if (!api.token.value || !api.user.value)
    return { path: '/login', query: { return: to.fullPath } }
})
router.afterEach((to) => {
  document.title =
    to.path === '/'
      ? 'SCENT · 让无形，成为风格。'
      : `${String(to.meta.title || 'SCENT')} · SCENT`
})
export default router
