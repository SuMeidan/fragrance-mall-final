<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
import { api, fail } from './state'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { computed } from 'vue'
import AdminIcon from './components/AdminIcon.vue'
import fontLicense from './assets/FONT-LICENSE.txt?url'
const route = useRoute(),
  router = useRouter()
const links = [
  ['/products', '商品'],
  ['/categories', '分类'],
  ['/orders', '订单'],
  ['/after-sales', '售后'],
  ['/finance', '账目'],
  ['/accounts', '买家'],
  ['/audits', '操作记录'],
]
const section = computed(
  () => links.find(([path]) => path === route.path)?.[1] || '商城管理',
)
async function logout() {
  try {
    await api.logout()
  } catch (e) {
    fail(e)
  } finally {
    router.replace('/login')
  }
}
</script>
<template>
  <el-config-provider :locale="zhCn">
    <RouterView v-if="route.path === '/login'" />
    <div v-else class="admin-shell">
      <aside class="sidebar">
        <RouterLink to="/products" class="brand">
          SCENT
          <span>商城管理</span>
        </RouterLink>
        <nav aria-label="管理导航">
          <RouterLink v-for="[path, label] in links" :key="path" :to="path!">
            <AdminIcon :name="path!" />
            {{ label }}
          </RouterLink>
        </nav>
        <div class="sidebar-bottom">
          <a href="/" class="store-link">
            查看商城
            <span aria-hidden="true">↗</span>
          </a>
          <div class="sidebar-foot">
            <span class="identity-dot" aria-hidden="true"></span>
            <div>
              <span>{{ api.user.value?.displayName || '管理员' }}</span>
              <small>已登录 · 安全会话</small>
            </div>
            <el-button text @click="logout">退出</el-button>
          </div>
        </div>
      </aside>
      <div class="admin-stage">
        <header class="admin-topbar">
          <span>{{ section }}</span>
          <span>
            SCENT 工作空间
            <i aria-hidden="true">·</i>
            管理员
          </span>
        </header>
        <main class="workspace"><RouterView /></main>
        <footer class="workspace-footer">
          <span>SCENT · 让无形，成为风格。</span>
          <a :href="fontLicense" target="_blank" rel="noopener">字体许可</a>
        </footer>
      </div>
    </div>
  </el-config-provider>
</template>
