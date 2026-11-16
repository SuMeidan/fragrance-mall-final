import type { Product } from '@shared/models'

export function imageSource(path: string, width: 480 | 960) {
  if (/^\/media\/[a-f0-9]{32}\.(png|jpg)$/.test(path))
    return `${path}?width=${width}`
  if (/^\/assets\/(candle|diffuser)\.png$/.test(path))
    return path.replace('.png', `-${width}.webp`)
  return path
}

export function imageSet(path: string) {
  const small = imageSource(path, 480)
  return small === path
    ? undefined
    : `${small} 480w, ${imageSource(path, 960)} 960w`
}

export function homepageProducts(items: Product[]) {
  const selected: Product[] = []
  const images = new Set<string>()
  const add = (product: Product | undefined) => {
    if (!product || images.has(product.imagePath)) return
    selected.push(product)
    images.add(product.imagePath)
  }
  // Existing categories: candle, reed diffuser, incense, ready-made gift.
  for (const category of [101, 102, 301, 504])
    add(
      items.find(
        (product) => product.categoryId === category && product.stock > 0,
      ),
    )
  for (const product of items.filter((p) => p.familyId && p.stock > 0)) {
    if (selected.length === 4) break
    add(product)
  }
  return selected
}

export function familyDescription(description: string) {
  return description
    .replace('的项目口径', '的气味方向')
    .replace('在咨询中继续区分', '选购时留意')
}

export function productDescription(
  description: string | null,
  subtitle: string,
) {
  const paragraphs: string[] = [],
    notices: string[] = []
  for (const line of (description || '')
    .split(/\r?\n/)
    .map((s) => s.trim())
    .filter(Boolean)) {
    if (/^SCENT虚构教学商品|^图片为AI生成/.test(line)) notices.push(line)
    else if (
      line !== subtitle &&
      line !== `气味与风格：${subtitle}` &&
      line !== '选择建议：适合偏爱上述气味方向的人；明确排斥该香调者不选。' &&
      !/^产品用途：[^。\n]+，按固定规格独立销售。$/.test(line)
    )
      paragraphs.push(line)
  }
  return { paragraphs, notices }
}
