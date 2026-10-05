import { screen } from '@testing-library/react'
import MockAdapter from 'axios-mock-adapter'
import userEvent from '@testing-library/user-event'
import { useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../lib/api'
import { renderApp } from '../test/renderApp'

vi.mock('../components/search/ResultsMap', () => ({ ResultsMap: () => <div>Map</div> }))

function LocationProbe() {
  const l = useLocation()
  return <output data-testid="loc">{l.pathname + l.search}</output>
}

const states = [
  { id: 14, name: 'Maharashtra', code: 'MH', slug: 'maharashtra', type: 'STATE', capitalName: 'Mumbai', cityCount: 7 },
  { id: 32, name: 'Delhi', code: 'DL', slug: 'delhi', type: 'UT', capitalName: 'New Delhi', cityCount: 1 },
]

describe('HomePage and StatePage', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
    mock = new MockAdapter(api)
  })

  afterEach(() => mock.restore())

  it('lists states and union territories from the API', async () => {
    mock.onGet('/states').reply(200, states)
    renderApp('/')

    expect(await screen.findByRole('link', { name: /maharashtra/i })).toHaveAttribute('href', '/in/maharashtra')
    expect(screen.getByRole('link', { name: /delhi/i })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: /union territories/i })).toBeInTheDocument()
  })

  it('has the parking search form in the hero', async () => {
    mock.onGet('/states').reply(200, states)
    renderApp('/')

    expect(await screen.findByRole('button', { name: 'Search parking' })).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'Where are you going?' })).toBeInTheDocument()
    expect(screen.getByLabelText('From')).toBeInTheDocument()
    expect(screen.getByLabelText('Vehicle')).toHaveValue('FOUR_WHEELER')
    expect(screen.getByText(/popular:/i)).toBeInTheDocument()
  })

  it('searches a popular city for the default window and a car when its chip is clicked', async () => {
    mock.onGet('/states').reply(200, states)
    mock.onGet('/search').reply(200, {
      content: [], page: 0, size: 20, totalElements: 0, totalPages: 0, center: { lat: 18.5204, lng: 73.8567 }, radiusKm: 5, window: null,
    })
    renderApp('/', undefined, <LocationProbe />)

    await userEvent.click(await screen.findByRole('button', { name: 'Pune' }))

    const loc = await screen.findByTestId('loc')
    expect(loc).toHaveTextContent('/search?place=Pune%2C+Maharashtra&lat=18.5204&lng=73.8567&start=')
    expect(loc).toHaveTextContent('&end=')
    expect(loc).toHaveTextContent('&vehicle=FOUR_WHEELER')
  })

  it('links every city to its parking page', async () => {
    mock.onGet('/states/maharashtra').reply(200, {
      id: 14, name: 'Maharashtra', code: 'MH', slug: 'maharashtra', type: 'STATE', capitalName: 'Mumbai',
      cities: [
        { id: 2, name: 'Pune', slug: 'pune', lat: 18.52, lng: 73.85, capital: false, stateName: 'Maharashtra', stateCode: 'MH', stateSlug: 'maharashtra' },
      ],
    })
    renderApp('/in/maharashtra')

    expect(await screen.findByRole('link', { name: /pune/i })).toHaveAttribute('href', '/in/maharashtra/pune')
  })

  it('shows the cities of a state with the capital marked', async () => {
    mock.onGet('/states/maharashtra').reply(200, {
      id: 14, name: 'Maharashtra', code: 'MH', slug: 'maharashtra', type: 'STATE', capitalName: 'Mumbai',
      cities: [
        { id: 1, name: 'Mumbai', slug: 'mumbai', lat: 19.07, lng: 72.87, capital: true, stateName: 'Maharashtra', stateCode: 'MH', stateSlug: 'maharashtra' },
        { id: 2, name: 'Pune', slug: 'pune', lat: 18.52, lng: 73.85, capital: false, stateName: 'Maharashtra', stateCode: 'MH', stateSlug: 'maharashtra' },
      ],
    })
    renderApp('/in/maharashtra')

    expect(await screen.findByRole('heading', { name: 'Maharashtra' })).toBeInTheDocument()
    expect(screen.getByText('Pune')).toBeInTheDocument()
    expect(screen.getByText('Capital')).toBeInTheDocument()
  })

  it('shows not found for an unknown state', async () => {
    mock.onGet('/states/atlantis').reply(404, { code: 'NOT_FOUND', detail: 'State not found' })
    renderApp('/in/atlantis')

    expect(await screen.findByText(/this spot is empty/i)).toBeInTheDocument()
  })
})
