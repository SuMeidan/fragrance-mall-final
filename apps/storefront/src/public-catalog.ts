import { api } from './state'
import { query } from '@shared/api'
import type { Page, Product } from '@shared/models'

// Read the complete public candidate set, using the server's own search semantics.
// Short-lived shared promises avoid fetching the same metadata for each facet.
const reads = new Map<string, { at: number; value: Promise<Product[]> }>()
export function publicProducts(
  scope: { categoryId?: number; keyword?: string } = {},
) {
  const key = JSON.stringify(scope),
    cached = reads.get(key)
  if (cached && Date.now() - cached.at < 30000) return cached.value
  const value = (async () => {
    const items: Product[] = [],
      seen = new Set<number>()
    for (let page = 1; page <= 100; page++) {
      const result = await api.request<Page<Product>>(
        '/catalog/products' + query({ ...scope, page, pageSize: 40 }),
        {},
        false,
      )
      const fresh = result.items.filter((p) => !seen.has(p.id))
      fresh.forEach((p) => {
        items.push(p)
        seen.add(p.id)
      })
      if (items.length >= result.total || !result.items.length) return items
      if (!fresh.length) throw new Error('商品分页暂时未能完整读取，请重试。')
    }
    throw new Error('商品目录超出本次读取范围，请缩小分类。')
  })().catch((error) => {
    reads.delete(key)
    throw error
  })
  reads.set(key, { at: Date.now(), value })
  return value
}
