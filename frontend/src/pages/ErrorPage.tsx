import { RefreshCw, TriangleAlert } from 'lucide-react'
import { Button } from '../components/ui/Button'
import { usePageTitle } from '../lib/usePageTitle'

/**
 * The friendly page for a crash. It deliberately shows no technical detail, and uses a plain link for "Go home" (a
 * full page load) so it works even when the router or the app state is what broke.
 *
 * `stale` is the other cause of a failed page: a new version was deployed and this tab's old chunks are gone. A reload
 * fixes that, so it says so instead of apologising.
 */
export function ErrorPage({ stale = false }: { stale?: boolean }) {
  usePageTitle(stale ? 'New version available' : 'Something went wrong')
  const Icon = stale ? RefreshCw : TriangleAlert
  return (
    <section className="mx-auto max-w-lg px-4 py-24 text-center">
      <Icon aria-hidden className="mx-auto h-12 w-12 text-amber-600 dark:text-amber-400" />
      <h1 className="mt-4 text-2xl font-bold">{stale ? 'A new version of ParkEase is available' : 'Something went wrong'}</h1>
      <p className="mt-2 text-slate-600 dark:text-slate-400">
        {stale
          ? 'We have just updated the site. Reload to pick up the latest version and carry on.'
          : 'We hit an unexpected problem. Reloading usually fixes it; if it keeps happening, please try again in a few minutes.'}
      </p>
      <div className="mt-6 flex flex-wrap justify-center gap-3">
        <Button type="button" onClick={() => window.location.reload()}>Reload</Button>
        <a
          href="/"
          className="inline-flex items-center rounded-lg border border-slate-300 px-4 py-2 text-sm font-semibold text-slate-800 hover:bg-slate-50 dark:border-slate-700 dark:text-slate-100 dark:hover:bg-slate-800"
        >
          Go home
        </a>
      </div>
    </section>
  )
}
