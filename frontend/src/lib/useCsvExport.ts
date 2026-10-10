import { useState } from 'react'
import { toast } from 'sonner'
import { errorMessage } from './errors'

/**
 * The state of a "Download CSV" button: `run` performs the export, warns with `truncatedMessage` when the server cut
 * it short, and keeps the failure in `error` for the page to show.
 */
export function useCsvExport(download: () => Promise<{ truncated: boolean }>, truncatedMessage: string) {
  const [exporting, setExporting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function run() {
    setExporting(true)
    setError(null)
    try {
      const { truncated } = await download()
      if (truncated) toast.warning(truncatedMessage)
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setExporting(false)
    }
  }

  return { exporting, error, run }
}
