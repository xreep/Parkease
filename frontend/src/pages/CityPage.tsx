import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ResultCard, ResultCardSkeleton } from '../components/search/ResultCard'
import { ResultsMap } from '../components/search/ResultsMap'
import { SearchForm } from '../components/search/SearchForm'
import { Button } from '../components/ui/Button'
import { Spinner } from '../components/ui/Spinner'
import { toProblem } from '../lib/errors'
import { useCity, type City } from '../lib/locations'
import { toSearchParams, useSearch, type SearchParams } from '../lib/search'
import { NotFoundPage } from './NotFoundPage'

const CITY_RADIUS_KM = 15

function CityResults({ city }: { city: City }) {
  const navigate = useNavigate()
  const [highlightedId, setHighlightedId] = useState<number | null>(null)
  const place = `${city.name}, ${city.stateName}`
  const params: SearchParams = { place, lat: city.lat, lng: city.lng, radius: CITY_RADIUS_KM, sort: 'distance' }
  const { data, isPending, isError, refetch } = useSearch(params)
  const results = data?.content ?? []
  const searchPage = `/search?${toSearchParams(params).toString()}`

  return (
    <>
      <div className="mt-6">
        <SearchForm initial={{ place, lat: city.lat, lng: city.lng, radius: CITY_RADIUS_KM }} />
      </div>

      <div className="mt-8 grid gap-6 lg:grid-cols-2">
        <div className="min-w-0 space-y-3">
          {isPending ? (
            <div role="status" className="space-y-3">
              <span className="sr-only">Loading results…</span>
              {[0, 1, 2].map((i) => <ResultCardSkeleton key={i} />)}
            </div>
          ) : isError ? (
            <div role="alert" className="space-y-3 rounded-xl border border-red-200 bg-red-50 p-4 dark:border-red-900 dark:bg-red-950/40">
              <p className="text-sm text-red-700 dark:text-red-300">Couldn't load parking. Try again.</p>
              <Button type="button" variant="secondary" onClick={() => void refetch()}>Retry</Button>
            </div>
          ) : results.length === 0 ? (
            <div className="space-y-3 rounded-xl border border-dashed border-slate-300 p-6 text-center dark:border-slate-700">
              <p className="font-medium">{`No listed parking in ${city.name} yet.`}</p>
              <Link
                to="/register?role=OWNER"
                className="inline-block font-semibold text-brand-700 hover:underline dark:text-brand-400"
              >
                List your space
              </Link>
            </div>
          ) : (
            <>
              <p className="text-sm text-slate-600 dark:text-slate-400">
                {`${data.totalElements} parking ${data.totalElements === 1 ? 'spot' : 'spots'} within ${CITY_RADIUS_KM} km`}
              </p>
              {results.map((r) => (
                <ResultCard key={r.id} result={r} highlighted={r.id === highlightedId} onHighlight={setHighlightedId} />
              ))}
              <Link to={searchPage} className="inline-block text-sm font-semibold text-brand-700 hover:underline dark:text-brand-400">
                View all on the search page
              </Link>
            </>
          )}
        </div>

        <div className="order-first h-72 min-w-0 lg:sticky lg:top-20 lg:order-none lg:h-[32rem]">
          <ResultsMap
            results={results}
            center={{ lat: city.lat, lng: city.lng }}
            radiusKm={CITY_RADIUS_KM}
            highlightedId={highlightedId}
            loading={isPending}
            onMarkerClick={setHighlightedId}
            onSearchArea={(c) =>
              navigate({
                pathname: '/search',
                search: `?${toSearchParams({ ...params, lat: Number(c.lat.toFixed(5)), lng: Number(c.lng.toFixed(5)), place: 'Map area' }).toString()}`,
              })
            }
          />
        </div>
      </div>
    </>
  )
}

export function CityPage() {
  const { stateSlug = '', citySlug = '' } = useParams()
  const { data: city, isPending, error } = useCity(stateSlug, citySlug)

  if (error) {
    if (toProblem(error).status === 404) return <NotFoundPage />
    return <p className="px-4 py-24 text-center text-red-600">Could not load this page. Please refresh.</p>
  }
  if (isPending) {
    return (
      <div className="flex justify-center py-24">
        <Spinner className="h-8 w-8 text-brand-600" />
      </div>
    )
  }

  return (
    <section className="mx-auto max-w-7xl px-4 py-8 sm:px-6">
      <Link to={`/in/${city.stateSlug}`} className="text-sm font-medium text-brand-700 hover:underline dark:text-brand-400">
        {`← ${city.stateName}`}
      </Link>
      <h1 className="mt-3 text-3xl font-bold tracking-tight">{`Parking in ${city.name}`}</h1>
      <p className="mt-1 text-slate-600 dark:text-slate-400">{city.stateName}</p>
      <CityResults city={city} />
    </section>
  )
}
