import type { ReactNode } from 'react'
import { render } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { SearchResultDto } from '../../lib/search'
import { ResultsMap } from './ResultsMap'

// jsdom gives the map no size, so real clustering would fold every marker into one cluster.
vi.mock('react-leaflet-cluster', () => ({ default: ({ children }: { children: ReactNode }) => <>{children}</> }))

const r = (id: number, price: number, lat: number, lng: number): SearchResultDto => ({
  id, title: `Lot ${id}`, listingType: 'METRO', address: 'x', cityName: 'Pune', stateName: 'Maharashtra', lat, lng,
  distanceKm: 1, coverPhotoUrl: null, pricePerHour: price, pricePerDay: null, pricePerMonth: null, amenities: [],
  open24x7: false, avgRating: 0, reviewCount: 0, totalSlots: 3, freeSlots: null, quote: null,
})

describe('ResultsMap', () => {
  const results = [r(1, 40, 18.52, 73.85), r(2, 1200, 18.53, 73.86)]
  const props = { center: { lat: 18.5204, lng: 73.8567 }, radiusKm: 5, onMarkerClick: vi.fn(), onSearchArea: vi.fn() }

  it('draws a price pill per result and styles the highlighted one differently', () => {
    const { container, rerender } = render(<ResultsMap {...props} results={results} highlightedId={null} />)

    const pills = () => Array.from(container.querySelectorAll<HTMLElement>('.leaflet-marker-icon span'))
    expect(pills().map((p) => p.textContent)).toEqual(expect.arrayContaining(['₹40', '₹1,200']))
    const before = pills().find((p) => p.textContent === '₹40')!.getAttribute('style')

    rerender(<ResultsMap {...props} results={results} highlightedId={1} />)
    const after = pills().find((p) => p.textContent === '₹40')!.getAttribute('style')
    expect(after).not.toBe(before)
  })

  it('reports marker clicks', async () => {
    const onMarkerClick = vi.fn()
    const { container } = render(<ResultsMap {...props} onMarkerClick={onMarkerClick} results={results} highlightedId={null} />)

    const marker = container.querySelector<HTMLElement>('.leaflet-marker-icon[title="Lot 2"]')!
    await userEvent.click(marker)

    expect(onMarkerClick).toHaveBeenCalledWith(2)
  })

  it('does not offer "Search this area" until the map is moved away', () => {
    const { queryByRole } = render(<ResultsMap {...props} results={results} highlightedId={null} />)
    expect(queryByRole('button', { name: 'Search this area' })).not.toBeInTheDocument()
  })
})
