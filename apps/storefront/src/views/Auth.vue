<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { errorText, safeReturn } from '@shared/api'
import { api, addProduct, syncCart, toast } from '../state'
import heroImage from '../assets/refined/hero.webp'
const route = useRoute(),
  router = useRouter(),
  registering = computed(() => route.path === '/register')
const showPassword = ref(false)
const phone = ref(''),
  password = ref(''),
  displayName = ref(''),
  error = ref(''),
  busy = ref(false)
async function submit() {
  busy.value = true
  error.value = ''
  try {
    if (registering.value)
      await api.request(
        '/auth/register',
        {
          method: 'POST',
          body: JSON.stringify({
            phone: phone.value,
            password: password.value,
            displayName: displayName.value,
          }),
        },
        false,
      )
    await api.login(phone.value, password.value)
    password.value = ''
    const target = safeReturn(route.query.return, '/account'),
      add = Number(route.query.add)
    await router.replace(target)
    if (Number.isSafeInteger(add) && add > 0 && target === `/products/${add}`) {
      try {
        await addProduct(add)
      } catch (e) {
        toast(errorText(e))
      }
    } else await syncCart().catch(() => {})
  } catch (e) {
    error.value = errorText(e)
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <section class="auth-page">
    <div class="auth-art">
      <p class="eyebrow">A QUIET EXPRESSION</p>
      <h2>
        留一点时间，
        <br />
        给自己的偏好。
      </h2>
      <img
        :src="heroImage"
        alt="建筑光影中的SCENT陶瓷香薰蜡烛"
        decoding="async"
      />
    </div>
    <div class="auth-form">
      <p class="eyebrow">SCENT / YOUR ACCOUNT</p>
      <h1>{{ registering ? '初次见面。' : '欢迎回来。' }}</h1>
      <p>
        {{
          registering
            ? '创建账号，开始你的香气旅程。'
            : '登录后，继续你的选择。'
        }}
      </p>
      <form @submit.prevent="submit">
        <label v-if="registering">
          称呼
          <input
            v-model="displayName"
            required
            maxlength="32"
            autocomplete="nickname"
            placeholder="希望如何称呼你"
          />
        </label>
        <label>
          手机号
          <input
            v-model="phone"
            required
            type="tel"
            pattern="1[3-9][0-9]{9}"
            maxlength="11"
            autocomplete="username"
            placeholder="请输入手机号"
          />
        </label>
        <label>
          密码
          <span class="password-shell">
            <input
              v-model="password"
              aria-label="密码"
              required
              :type="showPassword ? 'text' : 'password'"
              minlength="8"
              maxlength="64"
              :autocomplete="registering ? 'new-password' : 'current-password'"
              placeholder="8—64个字符"
            />
            <button
              type="button"
              :aria-label="showPassword ? '隐藏密码' : '显示密码'"
              @click="showPassword = !showPassword"
            >
              {{ showPassword ? '隐藏' : '显示' }}
            </button>
          </span>
        </label>
        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
        <button class="btn full" :disabled="busy">
          {{ busy ? '请稍候…' : registering ? '创建账号并登录' : '登录' }}
        </button>
      </form>
      <p class="auth-switch">
        {{ registering ? '已经有账号？' : '还没有账号？' }}
        <RouterLink
          :to="{
            path: registering ? '/login' : '/register',
            query: route.query,
          }"
        >
          {{ registering ? '去登录' : '创建账号' }}
        </RouterLink>
      </p>
    </div>
  </section>
</template>
