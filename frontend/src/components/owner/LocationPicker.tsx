import { useEffect, useMemo } from 'react'
import L from 'leaflet'
import { MapContainer, Marker, TileLayer, useMap, useMapEvents } from 'react-leaflet'
import 'leaflet/dist/leaflet.css'

export type LatLng = { lat: number; lng: number }

export type LocationPickerProps = {
  value: LatLng | null
  center: LatLng
  onChange: (pos: LatLng) => void
  /** Zoom used when the map is (re)centred. Defaults to street level. */
  zoom?: number
  /** Locks the pin: no dragging and no click-to-place. */
  readOnly?: boolean
}

// An emerald pin built from HTML/CSS, so Leaflet's default image assets are not needed.
// The 28px box is rotated -45deg around its centre, which swings its sharp bottom-left corner to
// 14 + 14*sqrt(2) ~ 34px below the box top, directly under the horizontal centre: that is the tip.
const pinIcon = L.divIcon({
  className: '',
  html: '<span style="display:block;width:28px;height:28px;border-radius:50% 50% 50% 0;transform:rotate(-45deg);background:#059669;border:3px solid #fff;box-shadow:0 2px 6px rgba(0,0,0,.4)"></span>',
  iconSize: [28, 28],
  iconAnchor: [14, 34],
})

function Recenter({ center, zoom }: { center: LatLng; zoom: number }) {
  const map = useMap()
  useEffect(() => {
    map.setView([center.lat, center.lng], zoom)
  }, [map, center.lat, center.lng, zoom])
  return null
}

function ClickToPlace({ onChange, disabled }: { onChange: (pos: LatLng) => void; disabled: boolean }) {
  useMapEvents({
    click: (e) => {
      if (!disabled) onChange({ lat: e.latlng.lat, lng: e.latlng.lng })
    },
  })
  return null
}

export function LocationPicker({ value, center, onChange, zoom = 13, readOnly = false }: LocationPickerProps) {
  const handlers = useMemo(
    () => ({
      dragend: (e: L.LeafletEvent) => {
        const { lat, lng } = (e.target as L.Marker).getLatLng()
        onChange({ lat, lng })
      },
    }),
    [onChange],
  )

  return (
    <div className="space-y-2">
      <MapContainer
        center={[center.lat, center.lng]}
        zoom={zoom}
        scrollWheelZoom={false}
        className="z-0 h-80 w-full rounded-xl border border-slate-300 dark:border-slate-700"
        style={{ height: 320 }}
      >
        <TileLayer
          attribution="© OpenStreetMap contributors"
          url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
        />
        <Recenter center={center} zoom={zoom} />
        <ClickToPlace onChange={onChange} disabled={readOnly} />
        {value && <Marker position={[value.lat, value.lng]} icon={pinIcon} draggable={!readOnly} eventHandlers={handlers} />}
      </MapContainer>
      <p className="text-sm text-slate-600 dark:text-slate-400">
        {value ? `Lat ${value.lat.toFixed(5)}, Lng ${value.lng.toFixed(5)}` : readOnly ? 'No pin set' : 'Click the map to drop a pin'}
      </p>
    </div>
  )
}
