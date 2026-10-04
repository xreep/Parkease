import { Link, useParams } from 'react-router-dom'
import { MapPin, Star } from 'lucide-react'
import { Spinner } from '../components/ui/Spinner'
import { toProblem } from '../lib/errors'
import { useStateDetail } from '../lib/locations'
import { NotFoundPage } from './NotFoundPage'

export function StatePage() {
  const { stateSlug = '' } = useParams()
  const { data: state, isLoading, error } = useStateDetail(stateSlug)

  if (isLoading) {
    return (
      <div className="flex justify-center py-24">
        <Spinner className="h-8 w-8 text-brand-600" />
      </div>
    )
  }
  if (error) {
    if (toProblem(error).status === 404) return <NotFoundPage />
    return <p className="px-4 py-24 text-center text-red-600">Could not load this page. Please refresh.</p>
  }
  if (!state) return null

  return (
    <section className="mx-auto max-w-7xl px-4 py-12 sm:px-6">
      <Link to="/#browse" className="text-sm font-medium text-brand-700 hover:underline dark:text-brand-400">← All states</Link>
      <p className="mt-4 text-sm font-semibold uppercase tracking-wide text-slate-500">
        {state.type === 'UT' ? 'Union territory' : 'State'}
      </p>
      <h1 className="text-3xl font-bold tracking-tight">{state.name}</h1>
      <p className="mt-1 text-slate-600 dark:text-slate-400">Capital: {state.capitalName}</p>
      <div className="mt-8 grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {state.cities.map((city) => (
          <div key={city.id} className="flex items-start justify-between rounded-xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
            <div className="flex gap-3">
              <MapPin className="mt-0.5 h-5 w-5 text-brand-600" />
              <div>
                <p className="font-semibold">{city.name}</p>
                <p className="text-xs text-slate-500">{city.lat.toFixed(3)}°N, {city.lng.toFixed(3)}°E</p>
              </div>
            </div>
            {city.capital && (
              <span className="inline-flex items-center gap-1 rounded-full bg-amber-100 px-2 py-0.5 text-xs font-semibold text-amber-800 dark:bg-amber-900/40 dark:text-amber-300">
                <Star className="h-3 w-3" />Capital
              </span>
            )}
          </div>
        ))}
      </div>
    </section>
  )
}
