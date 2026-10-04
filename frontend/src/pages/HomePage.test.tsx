import { screen } from '@testing-library/react'
import MockAdapter from 'axios-mock-adapter'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { api } from '../lib/api'
import { renderApp } from '../test/renderApp'

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
