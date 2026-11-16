<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { errorText, safeReturn } from '@shared/api'
import { api } from '../state'
import hero from '../assets/hero.webp'
const route = useRoute(),
  router = useRouter(),
  form = reactive({ loginName: '', password: '' }),
  busy = ref(false),
  error = ref('')
async function submit() {
  busy.value = true
  error.value = ''
  try {
    await api.login(form.loginName, form.password)
    form.password = ''
    await router.replace(safeReturn(route.query.return, '/products'))
  } catch (e) {
    error.value = errorText(e)
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <main class="admin-login">
    <section class="login-image" aria-label="SCENT 香氛品牌影像">
      <img
        :src="hero"
        alt="建筑光影中的SCENT陶瓷香氛蜡烛"
        fetchpriority="high"
      />
      <a class="login-brand" href="/">SCENT</a>
      <div class="login-quote">
        <h2>让无形，成为风格。</h2>
        <p>
          用心照料每一件作品，
          <br />
          也照料每一次相遇。
        </p>
      </div>
      <small>SCENT / 商城管理</small>
    </section>
    <section class="login-workspace">
      <div class="login-top">
        <span>商城管理</span>
        <a href="/">返回商城 ↗</a>
      </div>
      <form @submit.prevent="submit">
        <p class="eyebrow">SCENT ADMINISTRATION</p>
        <h1>欢迎回来</h1>
        <p class="login-description">登录管理工作空间。</p>
        <label>
          管理员账号
          <el-input
            v-model="form.loginName"
            aria-label="账号"
            placeholder="请输入账号"
            required
            maxlength="32"
            autocomplete="username"
          />
        </label>
        <label>
          登录密码
          <el-input
            v-model="form.password"
            aria-label="密码"
            placeholder="请输入密码"
            required
            type="password"
            minlength="8"
            maxlength="64"
            autocomplete="current-password"
            show-password
          />
        </label>
        <el-alert v-if="error" :title="error" type="error" :closable="false" />
        <el-button
          type="primary"
          native-type="submit"
          :loading="busy"
          aria-label="登录"
        >
          登录工作空间
          <span aria-hidden="true">→</span>
        </el-button>
        <p class="login-help">
          请使用管理员分配的账号。
          <br />
          登录遇到问题，请联系系统维护人员。
        </p>
      </form>
      <small class="login-footer">SCENT · 安全管理入口</small>
    </section>
  </main>
</template>
