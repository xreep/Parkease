import { api } from './api'
import type { City } from './locations'

export type Place = { label: string; lat: number; lng: number; kind: 'city' | 'place' }

export async function searchCities(q: string, signal?: AbortSignal): Promise<Place[]> {
  const { data } = await api.get<City[]>('/cities', { params: { q, limit: 5 }, signal })
  return data.map((c) => ({ label: `${c.name}, ${c.stateName}`, lat: c.lat, lng: c.lng, kind: 'city' }))
}

type NominatimHit = { display_name: string; lat: string; lon: string }

/** "Andheri Metro Station, Andheri East, Mumbai, Maharashtra, India" becomes its first three parts. */
const shorten = (displayName: string) =>
  displayName.split(',').slice(0, 3).map((s) => s.trim()).join(', ')

export async function searchNominatim(q: string, signal?: AbortSignal): Promise<Place[]> {
  const res = await fetch(
    'https://nominatim.openstreetmap.org/search?format=json&limit=5&countrycodes=in&q=' + encodeURIComponent(q),
    { signal },
  )
  if (!res.ok) throw new Error(`Nominatim responded ${res.status}`)
  const hits = (await res.json()) as NominatimHit[]
  return hits
    .map((h) => ({ label: shorten(h.display_name), lat: Number(h.lat), lng: Number(h.lon), kind: 'place' as const }))
    .filter((p) => Number.isFinite(p.lat) && Number.isFinite(p.lng))
}
