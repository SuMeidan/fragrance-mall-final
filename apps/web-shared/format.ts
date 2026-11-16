export const money = (cents: number) =>
  new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' }).format(
    cents / 100,
  )
export const dateTime = (date: string | null) =>
  date
    ? new Intl.DateTimeFormat('zh-CN', {
        dateStyle: 'medium',
        timeStyle: 'short',
        timeZone: 'Asia/Shanghai',
      }).format(new Date(date))
    : '—'
export function asset(path: string) {
  return path.startsWith('/assets/')
    ? `${import.meta.env.BASE_URL}${path.slice(1)}`
    : path
}
export function addressText(a: {
  receiver: string
  phone: string
  region: string
  addressDetail: string
}) {
  return `${a.receiver} · ${a.phone}\n${a.region} ${a.addressDetail}`
}

export function todayShanghai() {
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat('en', {
      timeZone: 'Asia/Shanghai',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
    })
      .formatToParts(new Date())
      .map((p) => [p.type, p.value]),
  )
  return `${parts.year}-${parts.month}-${parts.day}`
}
