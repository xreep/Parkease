import { useRef, useState } from 'react'
import clsx from 'clsx'
import { ChevronDown, SlidersHorizontal } from 'lucide-react'
import { useSearchParams } from 'react-router-dom'
import { FiltersDrawer, FiltersPanel } from '../components/search/FiltersPanel'
import { ResultCard, ResultCardSkeleton } from '../components/search/ResultCard'
import { ResultsMap } from '../components/search/ResultsMap'
import { SearchForm } from '../components/search/SearchForm'
import { Button } from '../components/ui/Button'
import { Pagination } from '../components/ui/Pagination'
import { Select } from '../components/ui/Select'
import {
  DEFAULT_SORT,
  parseSearchParams,
  toSearchParams,
  useSearch,
  type SearchParams,
  type SearchSort,
} from '../lib/search'
import { activeFilterCount, filtersOf, NO_FILTERS, paramsOf, RADIUS_OPTIONS, type Filters } from '../lib/searchFilters'
import { toProblem } from '../lib/errors'
import { durationLabel, formatWindow } from '../lib/time'

const SORT_LABELS: Record<SearchSort, string> = {
  distance: 'Nearest',
  price: 'Lowest price',
  rating: 'Best rated',
}

type View = 'list' | 'map'

function SearchResults({ params }: { params: SearchParams }) {
  const [, setSearchParams] = useSearchParams()
  const [highlightedId, setHighlightedId] = useState<number | null>(null)
  const [view, setView] = useState<View>('list')
  const [filtersOpen, setFiltersOpen] = useState(false)
  const [sidebarFilters, setSidebarFilters] = useState(false)
  const headerRef = useRef<HTMLDivElement>(null)
  const listRef = useRef<HTMLDivElement>(null)
  const { data, error, isPending, isError, isPlaceholderData, refetch } = useSearch(params)

  const filters = filtersOf(params)
  const filterCount = activeFilterCount(filters)
  const hasWindow = Boolean(params.start && params.end)
  const results = data?.content ?? []
  /** Data for the previous search or page, shown dimmed while the new one loads. */
  const stale = isPlaceholderData
  const timesRejected = isError && toProblem(error).code === 'INVALID_TIME_RANGE'

  function go(next: SearchParams, replace: boolean) {
    setSearchParams(toSearchParams(next), { replace })
  }
  /** A filter or sort tweak: rewrites the URL in place and goes back to the first page. */
  const tweak = (patch: Partial<SearchParams>) => go({ ...params, ...patch, page: undefined }, true)
  const applyFilters = (f: Filters) => tweak(paramsOf(f))

  function focusTimes() {
    headerRef.current?.querySelector<HTMLInputElement>('input[type="datetime-local"]')?.focus()
  }

  /** Pushes a history entry so that Back returns to the previous page of results. */
  function changePage(page: number) {
    go({ ...params, page: page || undefined }, false)
    listRef.current?.scrollTo?.({ top: 0 })
    listRef.current?.scrollIntoView?.({ block: 'start' })
  }

  function searchArea(c: { lat: number; lng: number }) {
    go({ ...params, lat: Number(c.lat.toFixed(5)), lng: Number(c.lng.toFixed(5)), place: 'Map area', page: undefined }, false)
  }

  function showMarker(id: number) {
    setHighlightedId(id)
    document.getElementById(`result-${id}`)?.scrollIntoView?.({ block: 'nearest', behavior: 'smooth' })
  }

  const nextRadius = RADIUS_OPTIONS.find((km) => km > filters.radius)
  const place = params.place || 'the selected location'
  const minutes = hasWindow ? (new Date(params.end!).getTime() - new Date(params.start!).getTime()) / 60_000 : 0

  return (
    <div className="mx-auto max-w-7xl px-4 py-4 sm:px-6">
      <div ref={headerRef}>
        {/* Re-created whenever the search itself changes (back/forward, "Search this area"), but not for filters. */}
        <SearchForm
          key={[params.place, params.lat, params.lng, params.start, params.end, params.vehicle].join('|')}
          compact
          initial={params}
        />
      </div>

      <div className="mt-4 flex flex-wrap items-end justify-between gap-x-4 gap-y-3">
        <div className="min-w-0">
          <h1 className="text-lg font-semibold break-words">
            {data && !stale ? `${data.totalElements} parking ${data.totalElements === 1 ? 'spot' : 'spots'} near ${place}` : 'Searching for parking…'}
          </h1>
          {hasWindow && (
            <p className="text-sm text-slate-600 dark:text-slate-400">
              {`${formatWindow(params.start!, params.end!)} · ${durationLabel(minutes)}`}
            </p>
          )}
        </div>
        <div className="flex min-w-0 flex-wrap items-end gap-2">
          <Button type="button" variant="secondary" className="lg:hidden" onClick={() => setFiltersOpen(true)}>
            <SlidersHorizontal aria-hidden className="h-4 w-4" />
            {filterCount > 0 ? `Filters (${filterCount})` : 'Filters'}
          </Button>
          <Select
            label="Sort by"
            className="min-w-0 flex-1 sm:flex-none"
            value={params.sort ?? DEFAULT_SORT}
            onChange={(e) => {
              const sort = e.target.value as SearchSort
              tweak({ sort: sort === DEFAULT_SORT ? undefined : sort })
            }}
          >
            {(Object.keys(SORT_LABELS) as SearchSort[]).map((s) => (
              <option key={s} value={s}>{SORT_LABELS[s]}</option>
            ))}
          </Select>
          <div role="group" aria-label="Results view" className="flex shrink-0 gap-1 lg:hidden">
            {(['list', 'map'] as const).map((v) => (
              <Button
                key={v}
                type="button"
                variant={view === v ? 'primary' : 'secondary'}
                aria-pressed={view === v}
                onClick={() => setView(v)}
              >
                {v === 'list' ? 'List' : 'Map'}
              </Button>
            ))}
          </div>
        </div>
      </div>

      <FiltersDrawer open={filtersOpen} onClose={() => setFiltersOpen(false)} value={filters} onApply={applyFilters} />

      <div className="mt-4 grid gap-4 lg:h-[calc(100dvh-13rem)] lg:min-h-[28rem] lg:grid-cols-2">
        <div
          ref={listRef}
          className={clsx('min-w-0 space-y-3 lg:overflow-y-auto lg:pr-2', view === 'map' && 'max-lg:hidden')}
        >
          <div className="hidden lg:block">
            <Button
              type="button"
              variant="secondary"
              aria-expanded={sidebarFilters}
              aria-controls="search-filters"
              onClick={() => setSidebarFilters((o) => !o)}
            >
              <SlidersHorizontal aria-hidden className="h-4 w-4" />
              {`${sidebarFilters ? 'Hide' : 'Show'} filters${filterCount > 0 ? ` (${filterCount})` : ''}`}
              <ChevronDown aria-hidden className={clsx('h-4 w-4 transition', sidebarFilters && 'rotate-180')} />
            </Button>
            {sidebarFilters && (
              <section
                id="search-filters"
                aria-label="Filters"
                className="mt-3 rounded-xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900"
              >
                <FiltersPanel
                  value={filters}
                  onChange={(patch) => applyFilters({ ...filters, ...patch })}
                  onClear={() => applyFilters({ ...NO_FILTERS })}
                />
              </section>
            )}
          </div>

          {isPending || (stale && results.length === 0) ? (
            <div role="status" className="space-y-3">
              <span className="sr-only">Loading results…</span>
              {[0, 1, 2].map((i) => <ResultCardSkeleton key={i} />)}
            </div>
          ) : isError ? (
            <div role="alert" className="space-y-3 rounded-xl border border-red-200 bg-red-50 p-4 dark:border-red-900 dark:bg-red-950/40">
              {timesRejected ? (
                <>
                  <p className="text-sm text-red-700 dark:text-red-300">
                    These times are no longer valid. Change the times to search again.
                  </p>
                  <Button type="button" variant="secondary" onClick={focusTimes}>Change times</Button>
                </>
              ) : (
                <>
                  <p className="text-sm text-red-700 dark:text-red-300">Couldn't load results. Try again.</p>
                  <Button type="button" variant="secondary" onClick={() => void refetch()}>Retry</Button>
                </>
              )}
            </div>
          ) : results.length === 0 ? (
            <div className="space-y-3 rounded-xl border border-dashed border-slate-300 p-6 text-center dark:border-slate-700">
              <p className="font-medium">
                {hasWindow ? 'No parking found here for these times.' : 'No parking found here.'}
              </p>
              <div className="flex flex-wrap justify-center gap-2">
                {nextRadius !== undefined && (
                  <Button type="button" variant="secondary" onClick={() => applyFilters({ ...filters, radius: nextRadius })}>
                    Try a larger distance
                  </Button>
                )}
                <Button type="button" variant="secondary" onClick={focusTimes}>
                  Try different times
                </Button>
              </div>
            </div>
          ) : (
            <div aria-busy={stale} className={clsx('space-y-3 transition-opacity', stale && 'opacity-60')}>
              {results.map((r) => (
                <ResultCard
                  key={r.id}
                  result={r}
                  start={params.start}
                  end={params.end}
                  vehicle={params.vehicle}
                  highlighted={r.id === highlightedId}
                  onHighlight={setHighlightedId}
                />
              ))}
            </div>
          )}

          {data && data.totalPages > 1 && <Pagination page={data.page} totalPages={data.totalPages} onChange={changePage} />}
        </div>

        <div className={clsx('h-[65vh] min-w-0 lg:sticky lg:top-20 lg:h-full', view === 'list' && 'max-lg:hidden')}>
          <ResultsMap
            results={results}
            center={{ lat: params.lat, lng: params.lng }}
            radiusKm={filters.radius}
            highlightedId={highlightedId}
            loading={isPending || stale}
            onMarkerClick={showMarker}
            onSearchArea={searchArea}
          />
        </div>
      </div>
    </div>
  )
}

export function SearchPage() {
  const [searchParams] = useSearchParams()
  const params = parseSearchParams(searchParams)

  if (!params) {
    return (
      <div className="mx-auto max-w-2xl px-4 py-12 sm:px-6">
        <h1 className="text-3xl font-bold tracking-tight">Find parking</h1>
        <p className="mt-2 text-slate-600 dark:text-slate-400">Choose where you are going and when, and we will show the spots nearby.</p>
        <div className="mt-6">
          <SearchForm />
        </div>
      </div>
    )
  }
  return <SearchResults params={params} />
}
