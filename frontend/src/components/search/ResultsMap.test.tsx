import type { ReactNode } from 'react'
import L from 'leaflet'
import { act, render } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
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

  describe('when it starts hidden', () => {
    const size = { w: 0, h: 0 }
    const observers: ResizeObserverCallback[] = []

    afterEach(() => {
      vi.restoreAllMocks()
      vi.unstubAllGlobals()
      observers.length = 0
      size.w = size.h = 0
    })

    it('waits for a size before fitting, then fits to a finite zoom', () => {
      // jsdom has no layout: give the map container a controllable size and capture the ResizeObserver.
      vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockImplementation(() => size.w)
      vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockImplementation(() => size.h)
      vi.stubGlobal('ResizeObserver', class {
        constructor(cb: ResizeObserverCallback) { observers.push(cb) }
        observe() {}
        disconnect() {}
      })
      const fitBounds = vi.spyOn(L.Map.prototype, 'fitBounds')

      render(<ResultsMap {...props} results={results} highlightedId={null} />)
      expect(fitBounds).not.toHaveBeenCalled()

      size.w = 400
      size.h = 300
      act(() => observers.forEach((cb) => cb([], {} as ResizeObserver)))

      expect(fitBounds).toHaveBeenCalledTimes(1)
      const map = fitBounds.mock.contexts[0] as L.Map
      expect(Number.isFinite(map.getZoom())).toBe(true)
      expect(Number.isFinite(map.getCenter().lat)).toBe(true)
    })

    it('does not refit for the same search when only the results change', () => {
      vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(400)
      vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(300)
      const fitBounds = vi.spyOn(L.Map.prototype, 'fitBounds')

      const { rerender } = render(<ResultsMap {...props} results={results} highlightedId={null} />)
      expect(fitBounds).toHaveBeenCalledTimes(1)

      rerender(<ResultsMap {...props} results={[results[1]]} highlightedId={null} />)
      expect(fitBounds).toHaveBeenCalledTimes(1)

      rerender(<ResultsMap {...props} center={{ lat: 19, lng: 73 }} results={results} highlightedId={null} />)
      expect(fitBounds).toHaveBeenCalledTimes(2)
    })
  })
})
