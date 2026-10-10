import { Spinner } from './ui/Spinner'

/** Shown while a route's code is still loading, so the page never flashes blank. */
export function PageFallback() {
  return (
    <div role="status" aria-label="Loading page" className="flex justify-center py-24">
      <Spinner className="h-8 w-8 text-brand-700 dark:text-brand-400" />
    </div>
  )
}
