import { api } from './api'

export const MAX_UPLOAD_BYTES = 5 * 1024 * 1024

/** POSTs a multipart form with `file` plus any extra text fields; rejects oversize files before sending. */
export async function uploadFile<T>(
  url: string,
  file: File,
  fields: Record<string, string> = {},
  onProgress?: (pct: number) => void,
): Promise<T> {
  if (file.size > MAX_UPLOAD_BYTES) throw new Error('Files must be 5 MB or smaller')
  const form = new FormData()
  for (const [key, value] of Object.entries(fields)) form.append(key, value)
  form.append('file', file)
  const { data } = await api.post<T>(url, form, {
    onUploadProgress: (e) => {
      if (onProgress && e.total) onProgress(Math.round((e.loaded / e.total) * 100))
    },
  })
  return data
}
