import { onScopeDispose, ref } from 'vue'
import type { Order } from './models'

// The local clock controls payment entry only. Closing an order and releasing
// stock still require the server's authoritative payment/closure checks.
export function useOrderTime() {
  const now = ref(Date.now())
  const timer = window.setInterval(() => (now.value = Date.now()), 1000)
  onScopeDispose(() => window.clearInterval(timer))
  return (order?: Pick<Order, 'status' | 'expiresAt'>) =>
    !!order && order.status === 0 && Date.parse(order.expiresAt) <= now.value
}
