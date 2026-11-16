import { HttpError } from '@shared/api'

type Input = {
  conversationId: string
  requestId: string
  turn: number
  message: string
}
const interrupted = () =>
  new HttpError(
    0,
    'ADVISOR_INTERRUPTED',
    '回复尚未完成，请继续等待原问题的结果。',
  )

// Fetch supports POST and explicit cancellation. Native EventSource only supports GET.
export async function streamAdvisor(
  input: Input,
  signal: AbortSignal,
  update: (text: string) => void,
): Promise<string> {
  let reader: ReadableStreamDefaultReader<Uint8Array> | undefined
  try {
    const response = await fetch('/api/v1/advisor/chat/stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
      },
      body: JSON.stringify(input),
      signal,
    })
    if (!response.ok) {
      const body = await response.json().catch(() => null)
      throw new HttpError(
        response.status,
        body?.errorCode || 'ADVISOR_UPSTREAM',
        body?.message || '暂时无法连接顾问，请稍后重试。',
        body?.traceId || response.headers.get('X-Trace-Id') || '',
      )
    }
    if (
      !response.headers.get('Content-Type')?.includes('text/event-stream') ||
      !response.body
    )
      throw interrupted()
    reader = response.body.getReader()
    const decoder = new TextDecoder('utf-8', { fatal: true })
    let buffer = '',
      bytes = 0
    const text = (value: unknown) => {
      if (
        typeof value !== 'string' ||
        !value.trim() ||
        [...value].length > 6000 ||
        /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f]/.test(value)
      )
        throw new HttpError(
          502,
          'ADVISOR_BAD_RESPONSE',
          '顾问回复格式异常，请清空后重新咨询。',
        )
      return value
    }
    while (true) {
      const { value, done } = await reader.read()
      if (done) throw interrupted()
      bytes += value.byteLength
      if (bytes > 8 * 1024 * 1024) throw interrupted()
      buffer = (buffer + decoder.decode(value, { stream: true })).replaceAll(
        '\r\n',
        '\n',
      )
      let end: number
      while ((end = buffer.indexOf('\n\n')) >= 0) {
        if (end > 262144) throw interrupted()
        const frame = buffer.slice(0, end)
        buffer = buffer.slice(end + 2)
        const lines = frame.split('\n')
        const event = lines
          .find((line) => line.startsWith('event:'))
          ?.slice(6)
          .trim()
        if (!['text', 'done', 'error'].includes(event || '')) continue
        const raw = lines
          .filter((line) => line.startsWith('data:'))
          .map((line) => line.slice(5).trimStart())
          .join('\n')
        const payload = JSON.parse(raw)
        if (event === 'error')
          throw new HttpError(
            payload.status || 503,
            payload.errorCode || 'ADVISOR_UPSTREAM',
            payload.message || '回复中断，请继续等待原问题的结果。',
          )
        const message = text(payload.message)
        if (event === 'done') return message
        update(message)
      }
      if (buffer.length > 262144) throw interrupted()
    }
  } catch (failure) {
    if (failure instanceof HttpError) throw failure
    throw interrupted()
  } finally {
    await reader?.cancel().catch(() => {})
    reader?.releaseLock()
  }
}
