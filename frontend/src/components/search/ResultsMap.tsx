import { useEffect, useMemo, useRef, useState } from 'react'
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
  onMarkerClick: (id: number) => void
  onSearchArea: (center: LatLng) => void
}

/** Panning further than this share of the visible width, away from the searched centre, offers "Search this area". */
const MOVED_FRACTION = 0.2

const PILL_BASE =
  'display:flex;align-items:center;justify-content:center;height:28px;width:60px;border-radius:9999px;font:600 12px/1 system-ui,sans-serif;white-space:nowrap;box-shadow:0 2px 6px rgba(0,0,0,.35);'

function pillIcon(price: number, highlighted: boolean): L.DivIcon {
  const style = highlighted
    ? `${PILL_BASE}background:#047857;color:#fff;border:2px solid #fff;transform:scale(1.15);`
    : `${PILL_BASE}background:#fff;color:#065f46;border:2px solid #059669;`
  return L.divIcon({
    className: '',
    html: `<span style="${style}">${formatINR(price)}</span>`,
    iconSize: [60, 28],
    iconAnchor: [30, 14],
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
  onMovedAway: (away: boolean) => void
}

/** Fits the view to a new search, keeps the map sized to its box, and reports panning away from the searched place. */
function MapController({ results, center, radiusKm, onMovedAway }: ControllerProps) {
  const map = useMap()
  const fitting = useRef(false)

  // Fit once per new set of results or searched place, not on every re-render.
  const resultKey = results.map((r) => r.id).join(',')
  const resultsRef = useRef(results)
  useEffect(() => {
    resultsRef.current = results
  })
  useEffect(() => {
    const origin = L.latLng(center.lat, center.lng)
    const found = resultsRef.current
    // With no results, show the searched radius. Otherwise fit the results, mirrored through the
    // searched place so that it stays at the middle of the view.
    const bounds = found.length ? L.latLngBounds([origin, origin]) : origin.toBounds(radiusKm * 2000)
    for (const r of found) {
      bounds.extend([r.lat, r.lng])
      bounds.extend([2 * center.lat - r.lat, 2 * center.lng - r.lng])
    }
    fitting.current = true
    try {
      map.fitBounds(bounds, { padding: [40, 40], maxZoom: 16, animate: false })
    } finally {
      fitting.current = false
    }
    onMovedAway(false)
  }, [map, center.lat, center.lng, radiusKm, resultKey, onMovedAway])

  // The map may be created hidden (mobile list view); re-measure whenever its box changes.
  useEffect(() => {
    if (typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(() => map.invalidateSize())
    observer.observe(map.getContainer())
    return () => observer.disconnect()
  }, [map])

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

export function ResultsMap({ results, center, radiusKm, highlightedId, onMarkerClick, onSearchArea }: ResultsMapProps) {
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
          attribution="© OpenStreetMap contributors"
          url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
        />
        <MapController results={results} center={center} radiusKm={radiusKm} onMovedAway={setMovedAway} />
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
