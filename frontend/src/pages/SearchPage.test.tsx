import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import MockAdapter from 'axios-mock-adapter'
import { useLocation, useNavigate } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../lib/api'
import type { SearchResponse, SearchResultDto } from '../lib/search'
import { nextQuarter } from '../lib/time'
import { renderApp } from '../test/renderApp'

vi.mock('../components/search/ResultsMap', () => ({
  ResultsMap: ({
    results,
    onMarkerClick,
    onSearchArea,
  }: {
    results: SearchResultDto[]
    onMarkerClick: (id: number) => void
    onSearchArea: (c: { lat: number; lng: number }) => void
  }) => {
    const navigate = useNavigate()
    return (
    <div>
      <output data-testid="url">{useLocation().search}</output>
      <button type="button" onClick={() => navigate(-1)}>Go back</button>
      {results.map((r) => (
        <button key={r.id} type="button" onClick={() => onMarkerClick(r.id)}>{`Marker ${r.title}`}</button>
      ))}
      <button type="button" onClick={() => onSearchArea({ lat: 18.6, lng: 73.9 })}>Search this area</button>
    </div>
    )
  },
}))

const DAY = 24 * 60 * 60 * 1000
const start = new Date(nextQuarter().getTime() + DAY).toISOString()
const end = new Date(nextQuarter().getTime() + DAY + 2 * 60 * 60 * 1000).toISOString()

const SEARCH_URL = `/search?place=${encodeURIComponent('Pune, Maharashtra')}&lat=18.5204&lng=73.8567&start=${encodeURIComponent(start)}&end=${encodeURIComponent(end)}&vehicle=FOUR_WHEELER`

const result = (over: Partial<SearchResultDto> = {}): SearchResultDto => ({
  id: 7, title: 'Metro Hub Parking', listingType: 'METRO', address: 'FC Road', cityName: 'Pune', stateName: 'Maharashtra',
  lat: 18.52, lng: 73.85, distanceKm: 0.8, coverPhotoUrl: null, pricePerHour: 40, pricePerDay: 250, pricePerMonth: 4500,
  amenities: ['CCTV', 'COVERED', 'WELL_LIT', 'EV_CHARGING'], open24x7: false, avgRating: 4.5, reviewCount: 12,
  totalSlots: 5, freeSlots: 3,
  quote: { pricingMode: 'HOURLY', durationMinutes: 120, baseAmount: 80, platformFee: 8, gstAmount: 1.44, totalAmount: 134.16, breakdown: '2 hours' },
  ...over,
})

const second = result({
  id: 9, title: 'Office Park Lot', listingType: 'OFFICE', distanceKm: 1.9, pricePerHour: 55, pricePerDay: null,
  pricePerMonth: null, amenities: [], reviewCount: 0, avgRating: 0, totalSlots: 8, freeSlots: 8, quote: null,
})

const response = (content: SearchResultDto[], over: Partial<SearchResponse> = {}): SearchResponse => ({
  content, page: 0, size: 20, totalElements: content.length, totalPages: 1,
  center: { lat: 18.5204, lng: 73.8567 }, radiusKm: 5, window: { start, end }, ...over,
})

describe('SearchPage', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
    mock = new MockAdapter(api)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => [] }))
  })

  afterEach(() => {
    mock.restore()
    vi.unstubAllGlobals()
  })

  const lastParams = () => mock.history.get.filter((r) => r.url === '/search').at(-1)!.params as Record<string, unknown>

  it('lists the results with prices, slots and the estimated total', async () => {
    mock.onGet('/search').reply(200, response([result(), second]))
    renderApp(SEARCH_URL)

    expect(await screen.findByText('2 parking spots near Pune, Maharashtra')).toBeInTheDocument()
    expect(lastParams()).toMatchObject({ lat: 18.5204, lng: 73.8567, start, end, vehicleType: 'FOUR_WHEELER' })

    const card = screen.getByRole('article', { name: 'Metro Hub Parking' })
    expect(within(card).getByRole('link', { name: 'Metro Hub Parking' }).getAttribute('href')).toContain('/listings/7?start=')
    expect(within(card).getByText('Metro / transit hub · 0.8 km')).toBeInTheDocument()
    expect(within(card).getByText('★ 4.5 (12)')).toBeInTheDocument()
    expect(within(card).getByText('₹40/hr')).toBeInTheDocument()
    expect(within(card).getByText('₹250/day')).toBeInTheDocument()
    expect(within(card).getByText('₹4,500/month')).toBeInTheDocument()
    expect(within(card).getByText('CCTV')).toBeInTheDocument()
    expect(within(card).getByText('+1')).toBeInTheDocument()
    expect(within(card).getByText('3 of 5 slots free')).toBeInTheDocument()
    expect(within(card).getByText('₹134.16 total')).toBeInTheDocument()

    const other = screen.getByRole('article', { name: 'Office Park Lot' })
    expect(within(other).queryByText(/★/)).not.toBeInTheDocument()
    expect(within(other).queryByText(/total/)).not.toBeInTheDocument()
  })

  it('re-requests with the chosen sort and writes it to the URL', async () => {
    mock.onGet('/search').reply(200, response([result(), second]))
    const user = userEvent.setup()
    renderApp(SEARCH_URL)
    await screen.findByText('2 parking spots near Pune, Maharashtra')

    await user.selectOptions(screen.getByLabelText('Sort by'), 'price')

    await waitFor(() => expect(lastParams().sort).toBe('price'))
    expect(screen.getByTestId('url').textContent).toContain('sort=price')
  })

  it('filters by amenity and clears the filters', async () => {
    mock.onGet('/search').reply(200, response([result()]))
    const user = userEvent.setup()
    renderApp(SEARCH_URL)
    await screen.findByText('1 parking spot near Pune, Maharashtra')

    expect(screen.queryByRole('checkbox', { name: 'CCTV' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Show filters' }))
    await user.click(screen.getByRole('checkbox', { name: 'CCTV' }))
    await waitFor(() => expect(lastParams().amenities).toEqual(['CCTV']))
    expect(screen.getByTestId('url').textContent).toContain('amenities=CCTV')

    await user.click(screen.getByRole('button', { name: 'Clear filters' }))
    // The unfiltered search is already cached, so the URL (not a new request) shows the filter is gone.
    await waitFor(() => expect(screen.getByTestId('url').textContent).not.toContain('amenities'))
    expect(screen.getByRole('checkbox', { name: 'CCTV' })).not.toBeChecked()
    expect(screen.getByRole('button', { name: 'Hide filters' })).toBeInTheDocument()
  })

  it('applies the distance and maximum price filters', async () => {
    mock.onGet('/search').reply(200, response([result()]))
    const user = userEvent.setup()
    renderApp(SEARCH_URL)
    await screen.findByText('1 parking spot near Pune, Maharashtra')

    await user.click(screen.getByRole('button', { name: 'Show filters' }))
    await user.selectOptions(screen.getByLabelText('Distance'), '10')
    await waitFor(() => expect(lastParams().radiusKm).toBe(10))

    await user.type(screen.getByLabelText('Max price per hour'), '80')
    await user.tab()
    await waitFor(() => expect(lastParams().maxPricePerHour).toBe(80))
    expect(screen.getByTestId('url').textContent).toContain('maxPrice=80')
  })

  it('does not apply a maximum price of zero or less', async () => {
    mock.onGet('/search').reply(200, response([result()]))
    const user = userEvent.setup()
    renderApp(SEARCH_URL)
    await screen.findByText('1 parking spot near Pune, Maharashtra')

    await user.click(screen.getByRole('button', { name: 'Show filters' }))
    await user.type(screen.getByLabelText('Max price per hour'), '0')
    await user.tab()

    expect(await screen.findByText('Enter a price above ₹0')).toBeInTheDocument()
    expect(screen.getByTestId('url').textContent).not.toContain('maxPrice')
    expect(mock.history.get.filter((r) => r.url === '/search')).toHaveLength(1)
  })

  it('offers a larger distance when nothing is found', async () => {
    mock.onGet('/search').reply(200, response([]))
    const user = userEvent.setup()
    renderApp(SEARCH_URL)

    expect(await screen.findByText('No parking found here for these times.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Try different times' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Try a larger distance' }))

    await waitFor(() => expect(lastParams().radiusKm).toBe(10))
  })

  it('searches the map area the user moved to', async () => {
    mock.onGet('/search').reply(200, response([result()]))
    const user = userEvent.setup()
    renderApp(SEARCH_URL)
    await screen.findByText('1 parking spot near Pune, Maharashtra')

    await user.click(screen.getByRole('button', { name: 'Search this area' }))

    await waitFor(() => expect(lastParams()).toMatchObject({ lat: 18.6, lng: 73.9 }))
    expect(await screen.findByText('1 parking spot near Map area')).toBeInTheDocument()
  })

  it('highlights the card of a clicked marker', async () => {
    mock.onGet('/search').reply(200, response([result(), second]))
    const user = userEvent.setup()
    renderApp(SEARCH_URL)
    await screen.findByText('2 parking spots near Pune, Maharashtra')

    expect(screen.getByRole('article', { name: 'Metro Hub Parking' })).toHaveAttribute('data-highlighted', 'false')
    await user.click(screen.getByRole('button', { name: 'Marker Metro Hub Parking' }))

    expect(screen.getByRole('article', { name: 'Metro Hub Parking' })).toHaveAttribute('data-highlighted', 'true')
    expect(screen.getByRole('article', { name: 'Office Park Lot' })).toHaveAttribute('data-highlighted', 'false')
  })

  it('highlights a card while hovered', async () => {
    mock.onGet('/search').reply(200, response([result(), second]))
    const user = userEvent.setup()
    renderApp(SEARCH_URL)
    await screen.findByText('2 parking spots near Pune, Maharashtra')

    const card = screen.getByRole('article', { name: 'Office Park Lot' })
    await user.hover(card)
    expect(card).toHaveAttribute('data-highlighted', 'true')
    await user.unhover(card)
    expect(card).toHaveAttribute('data-highlighted', 'false')
  })

  it('shows an error with a retry', async () => {
    mock.onGet('/search').replyOnce(500, { code: 'INTERNAL', detail: 'boom' })
    mock.onGet('/search').reply(200, response([result()]))
    const user = userEvent.setup()
    renderApp(SEARCH_URL)

    expect(await screen.findByText("Couldn't load results. Try again.")).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Retry' }))

    expect(await screen.findByText('1 parking spot near Pune, Maharashtra')).toBeInTheDocument()
  })

  it('pages through results', async () => {
    mock.onGet('/search').reply((config) => {
      const page = Number(config.params.page ?? 0)
      return [200, response([page === 0 ? result() : second], { page, totalPages: 2, totalElements: 2 })]
    })
    const user = userEvent.setup()
    renderApp(SEARCH_URL)

    expect(await screen.findByText('Page 1 of 2')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Next' }))

    expect(await screen.findByText('Page 2 of 2')).toBeInTheDocument()
    expect(lastParams().page).toBe(1)
    expect(await screen.findByRole('article', { name: 'Office Park Lot' })).toBeInTheDocument()

    // Each page is a history entry, so Back returns to the previous page.
    await user.click(screen.getByRole('button', { name: 'Go back' }))
    expect(await screen.findByText('Page 1 of 2')).toBeInTheDocument()
    expect(screen.getByTestId('url').textContent).not.toContain('page=')
  })

  it('does not show the previous search as the new one while loading', async () => {
    mock.onGet('/search').reply((config) =>
      config.params.lat === 18.6 ? new Promise(() => {}) : [200, response([result(), second])],
    )
    const user = userEvent.setup()
    renderApp(SEARCH_URL)
    await screen.findByText('2 parking spots near Pune, Maharashtra')

    await user.click(screen.getByRole('button', { name: 'Search this area' }))

    expect(await screen.findByText('Searching for parking…')).toBeInTheDocument()
    expect(screen.queryByText(/parking spots near/)).not.toBeInTheDocument()
  })

  it('shows a skeleton, not the empty state, while an empty search reloads', async () => {
    mock.onGet('/search').reply((config) =>
      config.params.radiusKm === 10 ? new Promise(() => {}) : [200, response([])],
    )
    const user = userEvent.setup()
    renderApp(SEARCH_URL)
    expect(await screen.findByText('Loading results…')).toBeInTheDocument()
    await screen.findByText('No parking found here for these times.')

    await user.click(screen.getByRole('button', { name: 'Try a larger distance' }))

    expect(await screen.findByText('Loading results…')).toBeInTheDocument()
    expect(screen.queryByText('No parking found here for these times.')).not.toBeInTheDocument()
  })

  it('asks for new times when the server rejects the time range', async () => {
    mock.onGet('/search').reply(400, { code: 'INVALID_TIME_RANGE', detail: 'Start is in the past' })
    const user = userEvent.setup()
    renderApp(SEARCH_URL)

    expect(await screen.findByText('These times are no longer valid. Change the times to search again.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Change times' }))

    expect(screen.getByLabelText('From')).toHaveFocus()
  })

  it('toggles between the list and the map on small screens', async () => {
    mock.onGet('/search').reply(200, response([result()]))
    const user = userEvent.setup()
    renderApp(SEARCH_URL)
    await screen.findByText('1 parking spot near Pune, Maharashtra')

    const list = screen.getByRole('button', { name: 'List' })
    const map = screen.getByRole('button', { name: 'Map' })
    expect(list).toHaveAttribute('aria-pressed', 'true')
    expect(map).toHaveAttribute('aria-pressed', 'false')

    await user.click(map)
    expect(map).toHaveAttribute('aria-pressed', 'true')
    expect(list).toHaveAttribute('aria-pressed', 'false')
  })

  it('applies filters from the mobile drawer', async () => {
    mock.onGet('/search').reply(200, response([result()]))
    const user = userEvent.setup()
    renderApp(SEARCH_URL)
    await screen.findByText('1 parking spot near Pune, Maharashtra')

    await user.click(screen.getByRole('button', { name: /^Filters/ }))
    const dialog = screen.getByRole('dialog', { name: 'Filters' })
    await user.click(within(dialog).getByRole('checkbox', { name: 'Open 24 × 7 only' }))
    expect(lastParams().open24x7).toBeUndefined()
    await user.click(within(dialog).getByRole('button', { name: 'Apply filters' }))

    await waitFor(() => expect(lastParams().open24x7).toBe(true))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('asks for a place when the coordinates are missing', () => {
    renderApp('/search')

    expect(screen.getByRole('heading', { name: 'Find parking' })).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: /where are you going/i })).toBeInTheDocument()
    expect(mock.history.get.filter((r) => r.url === '/search')).toHaveLength(0)
  })
})
