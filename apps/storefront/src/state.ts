import { ref } from 'vue'
import { createClient, errorText } from '@shared/api'
import type { Cart, CategoryTree, Family } from '@shared/models'

export const api = createClient('buyer')
export const cartCount = ref(0)
export const notice = ref('')
let timer: ReturnType<typeof setTimeout>
export function toast(text: string) {
  notice.value = text
  clearTimeout(timer)
  timer = setTimeout(() => {
    notice.value = ''
  }, 6000)
}
export async function syncCart() {
  if (!api.token.value) {
    cartCount.value = 0
    return
  }
  const cart = await api.request<Cart>('/cart')
  cartCount.value = cart.items.length
}
let catalogPromise:
  Promise<{ categories: CategoryTree[]; families: Family[] }> | undefined
export function catalog() {
  if (!catalogPromise)
    catalogPromise = Promise.all([
      api.request<CategoryTree[]>('/catalog/categories', {}, false),
      api.request<Family[]>('/catalog/families', {}, false),
    ])
      .then(([categories, families]) => ({ categories, families }))
      .catch((error) => {
        catalogPromise = undefined
        throw error
      })
  return catalogPromise
}
export async function addProduct(id: number) {
  await api.request('/cart', {
    method: 'POST',
    body: JSON.stringify({ productId: id }),
  })
  await syncCart().catch(() => {})
  toast('已加入购物车')
}
export const fail = (error: unknown) => toast(errorText(error))
