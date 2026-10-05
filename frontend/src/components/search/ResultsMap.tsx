import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import L from 'leaflet'
import { MapContainer, Marker, TileLayer, useMap, useMapEvents } from 'react-leaflet'
import MarkerClusterGroup from 'react-leaflet-cluster'
import 'leaflet/dist/leaflet.css'
import 'react-leaflet-cluster/dist/assets/MarkerCluster.css'
import 'react-leaflet-cluster/dist/assets/MarkerCluster.Default.css'
import { formatINR } from '../../lib/format'
import type { SearchResultDto } from '../../lib/search'
import { Button } from '../ui/Button'

export type LatLng = { lat: number; lng: number }

export type ResultsMapProps = {
  results: SearchResultDto[]
  /** The searched place. */
  center: LatLng
  radiusKm: number
  highlightedId: number | null
  /** True while the results are being (re)loaded or are a stale placeholder: the view then fits the searched place only. */
  loading?: boolean
  onMarkerClick: (id: number) => void
  onSearchArea: (center: LatLng) => void
}

/** Panning further than this share of the visible width, away from the searched centre, offers "Search this area". */
const MOVED_FRACTION = 0.2

// The marker box has no size; the pill is centred on the marker position, so any price width fits.
const PILL_BASE =
  'position:absolute;left:0;top:0;display:flex;align-items:center;justify-content:center;box-sizing:border-box;height:28px;min-width:60px;padding:0 8px;border-radius:9999px;font:600 12px/1 system-ui,sans-serif;white-space:nowrap;box-shadow:0 2px 6px rgba(0,0,0,.35);'

function pillIcon(price: number, highlighted: boolean): L.DivIcon {
  const style = highlighted
    ? `${PILL_BASE}background:#047857;color:#fff;border:2px solid #fff;transform:translate(-50%,-50%) scale(1.15);`
    : `${PILL_BASE}background:#fff;color:#065f46;border:2px solid #059669;transform:translate(-50%,-50%);`
  return L.divIcon({
    className: '',
    html: `<span style="${style}">${formatINR(price)}</span>`,
    iconSize: [0, 0],
    iconAnchor: [0, 0],
  })
}

const centerIcon = L.divIcon({
  className: '',
  html: '<span style="display:block;width:18px;height:18px;border-radius:50%;background:#2563eb;border:3px solid #fff;box-shadow:0 0 0 3px rgba(37,99,235,.35)"></span>',
  iconSize: [18, 18],
  iconAnchor: [9, 9],
})

type ControllerProps = {
  results: SearchResultDto[]
  center: LatLng
  radiusKm: number
  loading: boolean
  onMovedAway: (away: boolean) => void
}

/**
 * Fits the view when the searched place or radius changes (a new search), not when the same search is
 * paged, sorted or filtered. While results are loading it frames the searched radius, then fits the
 * results once they arrive. Keeps the map sized to its box and reports panning away from the searched place.
 */
function MapController({ results, center, radiusKm, loading, onMovedAway }: ControllerProps) {
  const map = useMap()
  const fitting = useRef(false)
  /** A fit was wanted while the map had no size (hidden on mobile); done once it is shown. */
  const needsFit = useRef(false)
  /** The search (place + radius) whose results the view has been fitted to. */
  const fittedFor = useRef<string | null>(null)
  const latest = useRef({ results, center, radiusKm, loading })
  useEffect(() => {
    latest.current = { results, center, radiusKm, loading }
  })

  const fit = useCallback(() => {
    const { results: found, center: c, radiusKm: km, loading: busy } = latest.current
    fitting.current = true
    try {
      map.invalidateSize({ animate: false })
      const size = map.getSize()
      // A hidden map is 0 x 0, and fitting that gives a NaN zoom. Wait until it has a size.
      if (size.x === 0 || size.y === 0) {
        needsFit.current = true
        return
      }
      needsFit.current = false
      const origin = L.latLng(c.lat, c.lng)
      // With nothing to show yet, frame the searched radius. Otherwise fit the results, mirrored
      // through the searched place so that it stays at the middle of the view.
      const useResults = !busy && found.length > 0
      const bounds = useResults ? L.latLngBounds([origin, origin]) : origin.toBounds(km * 2000)
      if (useResults) {
        for (const r of found) {
          bounds.extend([r.lat, r.lng])
          bounds.extend([2 * c.lat - r.lat, 2 * c.lng - r.lng])
        }
      }
      map.fitBounds(bounds, { padding: [40, 40], maxZoom: 16, animate: false })
      onMovedAway(false)
    } finally {
      fitting.current = false
    }
  }, [map, onMovedAway])

  useEffect(() => {
    const search = `${center.lat},${center.lng},${radiusKm}`
    if (fittedFor.current === search) return
    fit()
    if (!loading) fittedFor.current = search
  }, [fit, center.lat, center.lng, radiusKm, loading])

  // The map may be created hidden (mobile list view): re-measure whenever its box changes, and
  // complete a fit that was waiting for a size.
  useEffect(() => {
    if (typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(() => {
      if (needsFit.current) {
        fit()
      } else {
        fitting.current = true
        try {
          map.invalidateSize({ animate: false })
        } finally {
          fitting.current = false
        }
      }
    })
    observer.observe(map.getContainer())
    return () => observer.disconnect()
  }, [map, fit])

  useMapEvents({
    moveend: () => {
      if (fitting.current) return
      const bounds = map.getBounds()
      const width = map.distance(bounds.getSouthWest(), L.latLng(bounds.getSouth(), bounds.getEast()))
      const offset = map.distance(map.getCenter(), [center.lat, center.lng])
      onMovedAway(offset > MOVED_FRACTION * width)
    },
  })
  return null
}

export function ResultsMap({
  results,
  center,
  radiusKm,
  highlightedId,
  loading = false,
  onMarkerClick,
  onSearchArea,
}: ResultsMapProps) {
  const [movedAway, setMovedAway] = useState(false)
  const mapRef = useRef<L.Map | null>(null)
  const icons = useMemo(
    () => new Map(results.map((r) => [r.id, { normal: pillIcon(r.pricePerHour, false), highlighted: pillIcon(r.pricePerHour, true) }])),
    [results],
  )

  return (
    <div className="relative isolate h-full min-h-72 w-full overflow-hidden rounded-xl border border-slate-300 dark:border-slate-700">
      <MapContainer
        ref={mapRef}
        center={[center.lat, center.lng]}
        zoom={13}
        scrollWheelZoom
        className="z-0 h-full w-full"
      >
        <TileLayer
          attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
          url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
        />
        <MapController results={results} center={center} radiusKm={radiusKm} loading={loading} onMovedAway={setMovedAway} />
        <Marker position={[center.lat, center.lng]} icon={centerIcon} interactive={false} zIndexOffset={-1000} />
        <MarkerClusterGroup chunkedLoading showCoverageOnHover={false} maxClusterRadius={48}>
          {results.map((r) => (
            <Marker
              key={r.id}
              position={[r.lat, r.lng]}
              icon={icons.get(r.id)![r.id === highlightedId ? 'highlighted' : 'normal']}
              zIndexOffset={r.id === highlightedId ? 1000 : 0}
              title={r.title}
              alt={r.title}
              eventHandlers={{ click: () => onMarkerClick(r.id) }}
            />
          ))}
        </MarkerClusterGroup>
      </MapContainer>
      {movedAway && (
        <div className="absolute top-3 left-1/2 z-[1000] -translate-x-1/2">
          <Button
            type="button"
            variant="secondary"
            className="shadow-md"
            onClick={() => {
              const c = mapRef.current?.getCenter()
              if (c) onSearchArea({ lat: c.lat, lng: c.lng })
            }}
          >
            Search this area
          </Button>
        </div>
      )}
    </div>
  )
}
