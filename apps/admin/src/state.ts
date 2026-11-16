import { createClient, errorText } from '@shared/api'
import { ElMessage, ElMessageBox } from 'element-plus'
export const api = createClient('admin')
export const fail = (error: unknown) => ElMessage.error(errorText(error))
export async function confirm(
  message: string,
  title = '确认操作',
  confirmButtonText = '确认',
  destructive = false,
) {
  try {
    await ElMessageBox.confirm(message, title, {
      type: 'warning',
      confirmButtonText,
      confirmButtonClass: destructive ? 'danger-confirm' : '',
      cancelButtonText: '取消',
    })
    return true
  } catch {
    return false
  }
}
