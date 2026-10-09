/**
 * A failed blob request carries its problem as a Blob. Gives back what to throw: an Error with the server's `detail`
 * when the blob holds a problem body, otherwise the original error.
 */
export async function blobProblem(error: unknown): Promise<unknown> {
  const body = (error as { response?: { data?: unknown } } | null)?.response?.data
  if (body instanceof Blob) {
    try {
      const detail = (JSON.parse(await body.text()) as { detail?: unknown }).detail
      if (typeof detail === 'string' && detail) return new Error(detail)
    } catch {
      // Not a problem body: report the original error.
    }
  }
  return error
}

/** Hands a blob to the browser as a download. */
export function saveBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  link.remove()
  // Revoking right away can cancel the download in some browsers, so give it a moment.
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

/** The filename in a `Content-Disposition` header, if any. */
export function filenameFrom(disposition: unknown): string | null {
  if (typeof disposition !== 'string') return null
  const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(disposition)
  if (!match) return null
  try {
    return decodeURIComponent(match[1].trim())
  } catch {
    return match[1].trim()
  }
}
