import { createRouter, createWebHistory } from 'vue-router'
import { api } from './state'
const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', redirect: '/products' },
    { path: '/login', component: () => import('./views/Login.vue') },
    { path: '/products', component: () => import('./views/Products.vue') },
    { path: '/categories', component: () => import('./views/Categories.vue') },
    { path: '/orders', component: () => import('./views/Orders.vue') },
    { path: '/accounts', component: () => import('./views/Accounts.vue') },
    { path: '/audits', component: () => import('./views/Audits.vue') },
    { path: '/after-sales', component: () => import('./views/AfterSales.vue') },
    { path: '/finance', component: () => import('./views/Finance.vue') },
    { path: '/:pathMatch(.*)*', redirect: '/products' },
  ],
})
router.beforeEach(async (to) => {
  if (to.path === '/login') return
  if (api.token.value && !api.user.value) {
    try {
      await api.me()
    } catch {
      /* Login handles a revoked session. */
    }
  }
  if (!api.token.value || !api.user.value)
    return { path: '/login', query: { return: to.fullPath } }
})
export default router
