import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import MockAdapter from 'axios-mock-adapter'
import { useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../lib/api'
import type { SearchResponse, SearchResultDto } from '../lib/search'
import { renderApp } from '../test/renderApp'

vi.mock('../components/search/ResultsMap', () => ({
  ResultsMap: ({ results }: { results: SearchResultDto[] }) => <div data-testid="map">{`${results.length} on map`}</div>,
}))

function LocationProbe() {
  const l = useLocation()
  return <output data-testid="loc">{l.pathname + l.search}</output>
}

const pune = {
  id: 2, name: 'Pune', slug: 'pune', lat: 18.5204, lng: 73.8567, capital: false,
  stateName: 'Maharashtra', stateCode: 'MH', stateSlug: 'maharashtra',
}

const result: SearchResultDto = {
  id: 7, title: 'Metro Hub Parking', listingType: 'METRO', address: 'FC Road', cityName: 'Pune', stateName: 'Maharashtra',
  lat: 18.52, lng: 73.85, distanceKm: 0.8, coverPhotoUrl: null, pricePerHour: 40, pricePerDay: 250, pricePerMonth: null,
  amenities: [], open24x7: false, avgRating: 4.5, reviewCount: 12, totalSlots: 5, freeSlots: null, quote: null,
}

const response = (content: SearchResultDto[]): SearchResponse => ({
  content, page: 0, size: 20, totalElements: content.length, totalPages: content.length ? 1 : 0,
  center: { lat: pune.lat, lng: pune.lng }, radiusKm: 15, window: null,
})

describe('CityPage', () => {
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

  it('lists the parking within 15 km of the city, without a time window', async () => {
    mock.onGet('/states/maharashtra/cities/pune').reply(200, pune)
    mock.onGet('/search').reply(200, response([result]))
    renderApp('/in/maharashtra/pune')

    expect(await screen.findByRole('heading', { level: 1, name: 'Parking in Pune' })).toBeInTheDocument()
    expect(screen.getByText('Maharashtra')).toBeInTheDocument()
    expect(await screen.findByRole('link', { name: 'Metro Hub Parking' })).toHaveAttribute('href', '/listings/7')
    expect(screen.getByTestId('map')).toHaveTextContent('1 on map')

    const params = mock.history.get.find((r) => r.url === '/search')!.params as Record<string, unknown>
    expect(params).toMatchObject({ lat: 18.5204, lng: 73.8567, radiusKm: 15, sort: 'distance' })
    expect(params).not.toHaveProperty('start')
    expect(params).not.toHaveProperty('end')
  })

  it('prefills the search form with the city and links to the full search page', async () => {
    mock.onGet('/states/maharashtra/cities/pune').reply(200, pune)
    mock.onGet('/search').reply(200, response([result]))
    renderApp('/in/maharashtra/pune')

    expect(await screen.findByRole('combobox', { name: 'Where are you going?' })).toHaveValue('Pune, Maharashtra')
    expect(screen.getByRole('button', { name: 'Search parking' })).toBeInTheDocument()
    const all = await screen.findByRole('link', { name: /view all on the search page/i })
    expect(all).toHaveAttribute('href', expect.stringMatching(/^\/search\?place=Pune%2C\+Maharashtra&lat=18\.5204&lng=73\.8567&radius=15/))
  })

  it('searching from the form goes to the search page', async () => {
    mock.onGet('/states/maharashtra/cities/pune').reply(200, pune)
    mock.onGet('/search').reply(200, response([result]))
    renderApp('/in/maharashtra/pune', undefined, <LocationProbe />)

    await userEvent.click(await screen.findByRole('button', { name: 'Search parking' }))

    expect(await screen.findByTestId('loc')).toHaveTextContent('/search?place=Pune%2C+Maharashtra&lat=18.5204&lng=73.8567')
    expect(screen.getByTestId('loc').textContent).toMatch(/[?&]radius=15(&|$)/)
  })

  it('invites owners to list a space when the city has no parking yet', async () => {
    mock.onGet('/states/maharashtra/cities/pune').reply(200, pune)
    mock.onGet('/search').reply(200, response([]))
    renderApp('/in/maharashtra/pune')

    expect(await screen.findByText('No listed parking in Pune yet.')).toBeInTheDocument()
    const main = within(screen.getByRole('main'))
    expect(main.getByRole('link', { name: 'List your space' })).toHaveAttribute('href', '/register?role=OWNER')
    expect(main.queryByRole('link', { name: /view all on the search page/i })).not.toBeInTheDocument()
  })

  it('offers a retry when the results cannot be loaded', async () => {
    mock.onGet('/states/maharashtra/cities/pune').reply(200, pune)
    mock.onGet('/search').replyOnce(500).onGet('/search').reply(200, response([result]))
    renderApp('/in/maharashtra/pune')

    await userEvent.click(await screen.findByRole('button', { name: 'Retry' }))

    expect(await screen.findByRole('link', { name: 'Metro Hub Parking' })).toBeInTheDocument()
  })

  it('shows not found for an unknown city', async () => {
    mock.onGet('/states/maharashtra/cities/atlantis').reply(404, { code: 'NOT_FOUND', detail: 'City not found' })
    renderApp('/in/maharashtra/atlantis')

    expect(await screen.findByText(/this spot is empty/i)).toBeInTheDocument()
  })
})
