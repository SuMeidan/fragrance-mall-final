<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { ArrowUp, ChevronDown, X } from '@lucide/vue'
import { errorText, HttpError } from '@shared/api'
import type { Capabilities } from '@shared/models'
import { useRoute, useRouter } from 'vue-router'
import { renderAdvisorMarkdown } from '../advisor-markdown'
import { streamAdvisor } from '../advisor-stream'
import { api } from '../state'
type Message = {
  role: 'user' | 'assistant'
  content: string
  html?: string
  complete?: boolean
}
const router = useRouter()
const route = useRoute()
const quietRoute = computed(() =>
  /^\/(?:login|register|checkout|payment)(?:\/|$)/.test(route.path),
)
const open = ref(false),
  trigger = ref<HTMLButtonElement>(),
  panel = ref<HTMLElement>(),
  field = ref<HTMLTextAreaElement>(),
  log = ref<HTMLElement>()
const configured = ref<boolean | null>(null),
  loading = ref(false),
  pending = ref(false),
  needsRetry = ref(false),
  resetRequired = ref(false),
  error = ref(''),
  input = ref(''),
  messages = ref<Message[]>([])
let request: AbortController | undefined,
  generation = 0
let conversationId = crypto.randomUUID(),
  requestId = '',
  question = '',
  turn = 1
async function capability() {
  loading.value = true
  try {
    configured.value = (
      await api.request<Capabilities>('/capabilities', {}, false)
    ).advisorConfigured
  } catch (failure) {
    error.value = errorText(failure)
    configured.value = null
  } finally {
    loading.value = false
    await nextTick()
    if (open.value && configured.value) field.value?.focus()
  }
}
async function toggle() {
  open.value = !open.value
  await nextTick()
  if (open.value) {
    panel.value?.focus()
    if (configured.value !== true && !loading.value) void capability()
    else field.value?.focus()
  } else if (quietRoute.value) document.getElementById('main')?.focus()
  else trigger.value?.focus()
}
async function scroll() {
  await nextTick()
  log.value?.scrollTo({ top: log.value.scrollHeight, behavior: 'instant' })
}
async function send(text?: string) {
  if (
    pending.value ||
    needsRetry.value ||
    configured.value !== true ||
    resetRequired.value
  )
    return
  if (messages.value.at(-1)?.role === 'user') return
  if (turn > 30) {
    error.value = '本段咨询已满30轮，请清空后开始新的咨询。'
    resetRequired.value = true
    return
  }
  const content = (text ?? input.value).trim()
  if (!content || content.length > 600) return
  messages.value.push({ role: 'user', content })
  requestId = crypto.randomUUID()
  question = content
  input.value = ''
  await ask()
}
async function ask() {
  if (pending.value || !question) return
  const ticket = ++generation
  request = new AbortController()
  pending.value = true
  error.value = ''
  needsRetry.value = false
  let answerIndex =
    messages.value.at(-1)?.role === 'assistant' &&
    !messages.value.at(-1)?.complete
      ? messages.value.length - 1
      : -1
  let frame = 0,
    latest = ''
  function display(text: string, complete = false) {
    if (ticket !== generation) return
    if (answerIndex < 0) {
      answerIndex = messages.value.length
      messages.value.push({ role: 'assistant', content: '', complete: false })
    }
    messages.value[answerIndex] = {
      role: 'assistant',
      content: text,
      complete,
      html: renderAdvisorMarkdown(text, complete),
    }
    void scroll()
  }
  function progress(text: string) {
    latest = text
    if (!frame)
      frame = requestAnimationFrame(() => {
        frame = 0
        display(latest)
      })
  }
  void scroll()
  try {
    const reply = await streamAdvisor(
      {
        conversationId,
        requestId,
        turn,
        message: question,
      },
      AbortSignal.any([request.signal, AbortSignal.timeout(70000)]),
      progress,
    )
    if (ticket !== generation) return
    cancelAnimationFrame(frame)
    display(reply, true)
    question = ''
    turn++
    void scroll()
  } catch (failure) {
    if (ticket === generation) {
      if (latest.trim()) display(latest)
      error.value = errorText(failure)
      needsRetry.value = true
      resetRequired.value =
        failure instanceof HttpError &&
        [
          'ADVISOR_EXPIRED',
          'ADVISOR_RESET_REQUIRED',
          'ADVISOR_APPROVAL_REQUIRED',
          'ADVISOR_BAD_RESPONSE',
        ].includes(failure.code)
    }
  } finally {
    cancelAnimationFrame(frame)
    if (ticket === generation) {
      pending.value = false
      request = undefined
      await nextTick()
      if (open.value) field.value?.focus()
    }
  }
}
function clear() {
  const previous = conversationId
  const hadMessages = messages.value.length > 0
  generation++
  request?.abort()
  request = undefined
  pending.value = false
  needsRetry.value = false
  messages.value = []
  input.value = ''
  error.value = ''
  resetRequired.value = false
  conversationId = crypto.randomUUID()
  requestId = ''
  question = ''
  turn = 1
  if (hadMessages)
    void api
      .request<void>(
        '/advisor/conversations/' + previous,
        { method: 'DELETE' },
        false,
      )
      .catch(() => {})
  void nextTick(() => field.value?.focus())
}
function submit() {
  void send()
}
function inputKeydown(event: KeyboardEvent) {
  if (
    event.key !== 'Enter' ||
    event.shiftKey ||
    event.isComposing ||
    event.keyCode === 229
  )
    return
  event.preventDefault()
  submit()
}
function retry() {
  void ask()
}
function navigateProduct(event: MouseEvent) {
  const link = (event.target as Element).closest<HTMLAnchorElement>(
    'a.advisor-product-link',
  )
  if (
    !link ||
    event.button !== 0 ||
    event.ctrlKey ||
    event.metaKey ||
    event.shiftKey ||
    event.altKey
  )
    return
  const path = link.getAttribute('href') || ''
  if (/^\/products\/[1-9]\d{0,8}$/.test(path)) {
    event.preventDefault()
    void router.push(path)
  }
}
</script>
<template>
  <button
    ref="trigger"
    class="advisor-trigger"
    :class="{ 'advisor-quiet-route': quietRoute }"
    aria-label="香氛顾问"
    :aria-expanded="open"
    aria-controls="scent-advisor"
    @click="toggle"
  >
    <span class="advisor-trigger-copy" aria-hidden="true">
      <strong>香氛顾问</strong>
      <small v-if="!open">找到你的气味</small>
    </span>
    <ChevronDown v-if="open" :size="16" aria-hidden="true" />
  </button>
  <section
    v-if="open"
    id="scent-advisor"
    ref="panel"
    tabindex="-1"
    class="advisor-panel"
    role="region"
    aria-labelledby="advisor-title"
    @keydown.esc.stop.prevent="toggle"
  >
    <header class="advisor-header">
      <div class="advisor-identity">
        <div id="advisor-title">
          <strong>SCENT</strong>
          <span>香氛顾问</span>
        </div>
      </div>
      <div class="advisor-actions">
        <button
          class="advisor-icon-button"
          aria-label="开始新的香氛咨询"
          title="开始新的咨询"
          :disabled="!messages.length"
          @click="clear"
        >
          <span class="advisor-new-label">新咨询</span>
        </button>
        <button
          class="advisor-icon-button"
          aria-label="关闭香氛顾问"
          @click="toggle"
        >
          <X :size="18" aria-hidden="true" />
        </button>
      </div>
    </header>
    <div
      ref="log"
      class="advisor-log"
      :class="{ 'advisor-welcome': !messages.length }"
      role="log"
      aria-live="polite"
      aria-relevant="additions text"
      :aria-busy="pending"
    >
      <template v-if="!messages.length">
        <div class="advisor-welcome-top">
          <h2>
            想选一款
            <br />
            什么样的香气？
          </h2>
          <p>
            自用或送礼，都可以从这里聊起。
            <br />
            告诉我气味偏好、预算，或想避开的味道。
          </p>
        </div>
        <div class="advisor-welcome-content">
          <div class="advisor-suggestions" aria-label="选香话题">
            <button
              v-for="topic in [
                {
                  title: '清爽一点，不要甜香',
                  detail: '找到适合自己的日常气味',
                },
                { title: '想挑一份香氛礼物', detail: '让气味替你表达心意' },
                {
                  title: '想找一款不用点火的',
                  detail: '看看藤条、香片与扩香器具',
                },
              ]"
              :key="topic.title"
              :disabled="configured !== true || pending"
              @click="send(topic.title)"
            >
              <span>
                <strong>{{ topic.title }}</strong>
                <small>{{ topic.detail }}</small>
              </span>
            </button>
          </div>
        </div>
      </template>
      <article
        v-for="(message, index) in messages"
        :key="index"
        class="advisor-message"
        :class="[
          message.role,
          { incomplete: message.role === 'assistant' && !message.complete },
        ]"
      >
        <span v-if="message.role === 'assistant'" class="advisor-answer-label">
          SCENT AI
        </span>
        <span v-else class="sr-only">你</span>
        <div
          v-if="message.role === 'assistant'"
          class="advisor-markdown"
          @click="navigateProduct"
          v-html="message.html"
        />
        <p v-else>{{ message.content }}</p>
      </article>
      <p v-if="pending" class="advisor-thinking" role="status">
        <span class="advisor-thinking-dots" aria-hidden="true">
          <i />
          <i />
          <i />
        </span>
        {{
          messages.at(-1)?.role === 'assistant'
            ? '正在生成回复…'
            : '正在了解偏好、查询商品…'
        }}
      </p>
    </div>
    <p v-if="loading" class="advisor-notice" role="status">正在连接香氛顾问…</p>
    <div v-else-if="configured === false" class="advisor-notice" role="status">
      香氛顾问暂未开放，可以先浏览本店商品，稍后再来聊聊。
    </div>
    <div v-if="error" class="advisor-notice advisor-error" role="alert">
      {{ error }}
      <button
        v-if="needsRetry && !pending && !resetRequired"
        class="text-link"
        @click="retry"
      >
        继续等待结果
      </button>
      <button
        v-else-if="configured === null"
        class="text-link"
        @click="capability"
      >
        重新连接
      </button>
    </div>
    <div class="advisor-composer">
      <form class="advisor-form" @submit.prevent="submit">
        <label class="sr-only" for="advisor-input">描述你的气味偏好</label>
        <textarea
          id="advisor-input"
          ref="field"
          v-model="input"
          rows="2"
          maxlength="600"
          :disabled="configured !== true"
          placeholder="说说你想寻找的气味…"
          aria-describedby="advisor-input-help"
          @keydown="inputKeydown"
        />
        <button
          class="advisor-send"
          type="submit"
          :disabled="
            configured !== true ||
            pending ||
            needsRetry ||
            resetRequired ||
            messages.at(-1)?.role === 'user' ||
            !input.trim()
          "
          aria-label="发送香氛咨询"
        >
          <ArrowUp :size="18" aria-hidden="true" />
        </button>
      </form>
      <div id="advisor-input-help" class="sr-only">
        <span>请勿填写个人信息</span>
        <span>Enter 发送 · Shift + Enter 换行</span>
      </div>
      <footer class="advisor-footer">
        <span>AI 选香建议，请以商品说明为准</span>
        <span class="advisor-count">{{ input.length }} / 600</span>
        <button v-if="messages.length" class="text-link" @click="clear">
          清空
        </button>
        <RouterLink v-else class="text-link" to="/catalog" @click="toggle">
          浏览商品
        </RouterLink>
      </footer>
    </div>
  </section>
</template>
