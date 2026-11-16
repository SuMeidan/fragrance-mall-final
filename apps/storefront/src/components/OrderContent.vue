<script setup lang="ts">
import type { OrderDetail } from '@shared/models'
import { addressText, asset, dateTime, money } from '@shared/format'
import { useOrderTime } from '@shared/order-time'
defineProps<{ data: OrderDetail }>()
const isExpired = useOrderTime()
</script>
<template>
  <div class="order-meta">
    <p>
      订单号
      <strong>{{ data.order.orderNo }}</strong>
    </p>
    <p>下单时间 {{ dateTime(data.order.createdAt) }}</p>
    <p v-if="data.order.status === 0">
      {{ isExpired(data.order) ? '付款期限已过' : '付款期限' }}：{{
        dateTime(data.order.expiresAt)
      }}
      <span v-if="isExpired(data.order)">
        · 请查看付款状态，关闭结果以服务端核对为准。
      </span>
    </p>
    <p v-if="data.order.closedAt">
      关闭时间 {{ dateTime(data.order.closedAt) }} ·
      {{ data.order.closeReason }}
    </p>
    <p v-if="data.order.paidAt">支付时间 {{ dateTime(data.order.paidAt) }}</p>
    <p v-if="data.order.carrier">
      物流 {{ data.order.carrier }} · {{ data.order.trackingNo }}
    </p>
  </div>
  <div class="order-lines">
    <article v-for="item in data.items" :key="item.id">
      <img :src="asset(item.imagePath)" :alt="item.name" />
      <div>
        <h3>{{ item.name }}</h3>
        <p>{{ item.spec }} / {{ item.quantity }}{{ item.unit }}</p>
        <span>{{ money(item.priceCents) }}</span>
      </div>
      <strong>{{ money(item.priceCents * item.quantity) }}</strong>
    </article>
  </div>
  <div class="order-bottom">
    <div>
      <h3>收货信息</h3>
      <p class="preserve">{{ addressText(data.order) }}</p>
    </div>
    <dl>
      <div>
        <dt>运费</dt>
        <dd>由 SCENT 承担</dd>
      </div>
      <div>
        <dt>合计</dt>
        <dd class="serif">{{ money(data.order.totalCents) }}</dd>
      </div>
    </dl>
  </div>
</template>
