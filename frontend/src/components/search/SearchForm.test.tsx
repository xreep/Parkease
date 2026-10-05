import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import MockAdapter from 'axios-mock-adapter'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../lib/api'
import { SearchForm } from './SearchForm'

function Probe() {
  return <output data-testid="location">{useLocation().search}</output>
}

function renderForm(ui = <SearchForm />) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route path="/" element={ui} />
          <Route path="/search" element={<Probe />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const pune = {
  id: 1, name: 'Pune', slug: 'pune', lat: 18.5204, lng: 73.8567, capital: false,
  stateName: 'Maharashtra', stateCode: 'MH', stateSlug: 'maharashtra',
}

describe('SearchForm', () => {
  let mock: MockAdapter
  const fetchMock = vi.fn()

  beforeEach(() => {
    mock = new MockAdapter(api)
    fetchMock.mockReset()
    fetchMock.mockResolvedValue({ ok: true, json: async () => [] })
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    mock.restore()
    vi.unstubAllGlobals()
  })

  it('suggests cities and navigates to /search with the chosen place', async () => {
    mock.onGet('/cities').reply(200, [pune])
    const user = userEvent.setup()
    renderForm()

    await user.type(screen.getByRole('combobox', { name: /where are you going/i }), 'pun')
    await user.click(await screen.findByRole('option', { name: 'Pune, Maharashtra' }))
    await user.click(screen.getByRole('button', { name: /search parking/i }))

    const search = (await screen.findByTestId('location')).textContent!
    expect(search).toMatch(
      /^\?place=Pune%2C\+Maharashtra&lat=18\.5204&lng=73\.8567&start=[^&]+&end=[^&]+&vehicle=FOUR_WHEELER$/,
    )
    const q = new URLSearchParams(search)
    const minutes = (new Date(q.get('end')!).getTime() - new Date(q.get('start')!).getTime()) / 60000
    expect(minutes).toBe(120)
  })

  it('sends the selected vehicle type', async () => {
    mock.onGet('/cities').reply(200, [pune])
    const user = userEvent.setup()
    renderForm()

    await user.type(screen.getByRole('combobox', { name: /where are you going/i }), 'pun')
    await user.click(await screen.findByRole('option', { name: 'Pune, Maharashtra' }))
    await user.selectOptions(screen.getByLabelText('Vehicle'), 'TWO_WHEELER')
    await user.click(screen.getByRole('button', { name: /search parking/i }))

    expect((await screen.findByTestId('location')).textContent).toContain('vehicle=TWO_WHEELER')
  })

  it('requires a place from the list', async () => {
    const user = userEvent.setup()
    renderForm()

    await user.click(screen.getByRole('button', { name: /search parking/i }))

    expect(await screen.findByText('Choose a place from the list')).toBeInTheDocument()
    expect(screen.queryByTestId('location')).not.toBeInTheDocument()
  })

  it('does not accept free text that was never picked', async () => {
    mock.onGet('/cities').reply(200, [pune])
    const user = userEvent.setup()
    renderForm()

    await user.type(screen.getByRole('combobox', { name: /where are you going/i }), 'pun')
    await screen.findByRole('option', { name: 'Pune, Maharashtra' })
    await user.click(screen.getByRole('button', { name: /search parking/i }))

    expect(await screen.findByText('Choose a place from the list')).toBeInTheDocument()
  })

  it('rejects an end that is not after the start', async () => {
    mock.onGet('/cities').reply(200, [pune])
    const user = userEvent.setup()
    renderForm()

    await user.type(screen.getByRole('combobox', { name: /where are you going/i }), 'pun')
    await user.click(await screen.findByRole('option', { name: 'Pune, Maharashtra' }))
    const until = screen.getByLabelText('Until')
    await user.clear(until)
    await user.type(until, '2020-01-01T08:00')
    await user.click(screen.getByRole('button', { name: /search parking/i }))

    expect(await screen.findByText("'Until' must be after 'From'")).toBeInTheDocument()
  })

  it('rejects a booking shorter than one hour', async () => {
    mock.onGet('/cities').reply(200, [pune])
    const user = userEvent.setup()
    renderForm()

    await user.type(screen.getByRole('combobox', { name: /where are you going/i }), 'pun')
    await user.click(await screen.findByRole('option', { name: 'Pune, Maharashtra' }))
    const from = screen.getByLabelText('From')
    const until = screen.getByLabelText('Until')
    await user.clear(from)
    await user.type(from, '2030-01-01T08:00')
    await user.clear(until)
    await user.type(until, '2030-01-01T08:45')
    await user.click(screen.getByRole('button', { name: /search parking/i }))

    expect(await screen.findByText('Book at least 1 hour')).toBeInTheDocument()
  })

  it('looks up landmarks on Nominatim and shortens the label', async () => {
    mock.onGet('/cities').reply(200, [])
    fetchMock.mockResolvedValue({
      ok: true,
      json: async () => [
        {
          display_name: 'Andheri Metro Station, Andheri East, Mumbai, Maharashtra, 400069, India',
          lat: '19.1197',
          lon: '72.8464',
        },
      ],
    })
    const user = userEvent.setup()
    renderForm()

    await user.type(screen.getByRole('combobox', { name: /where are you going/i }), 'andheri metro')

    expect(await screen.findByRole('option', { name: 'Andheri Metro Station, Andheri East, Mumbai' })).toBeInTheDocument()
    expect(screen.getByText('Places')).toBeInTheDocument()
    const url = String(fetchMock.mock.calls.at(-1)![0])
    expect(url).toBe(
      'https://nominatim.openstreetmap.org/search?format=json&limit=5&countrycodes=in&q=andheri%20metro',
    )
  })

  it('lists cities before places and supports the keyboard', async () => {
    mock.onGet('/cities').reply(200, [pune])
    fetchMock.mockResolvedValue({
      ok: true,
      json: async () => [{ display_name: 'Pune Railway Station, Camp, Pune, India', lat: '18.528', lon: '73.874' }],
    })
    const user = userEvent.setup()
    renderForm()

    const input = screen.getByRole('combobox', { name: /where are you going/i })
    await user.type(input, 'pun')
    const listbox = screen.getByRole('listbox')
    await waitFor(() => expect(within(listbox).getAllByRole('option')).toHaveLength(2))
    const options = within(listbox).getAllByRole('option')
    expect(options.map((o) => o.textContent)).toEqual(['Pune, Maharashtra', 'Pune Railway Station, Camp, Pune'])
    expect(screen.getByText('Cities')).toBeInTheDocument()
    expect(input).toHaveAttribute('aria-expanded', 'true')

    await user.keyboard('{ArrowDown}{ArrowDown}')
    expect(input).toHaveAttribute('aria-activedescendant', options[1].id)
    await user.keyboard('{ArrowUp}{Enter}')

    expect(input).toHaveValue('Pune, Maharashtra')
    expect(input).toHaveAttribute('aria-expanded', 'false')
  })

  it('closes the list on Escape and shows No matches', async () => {
    mock.onGet('/cities').reply(200, [])
    const user = userEvent.setup()
    renderForm()

    const input = screen.getByRole('combobox', { name: /where are you going/i })
    await user.type(input, 'zz')
    expect(await screen.findByText('No matches')).toBeInTheDocument()

    await user.keyboard('{Escape}')
    expect(input).toHaveAttribute('aria-expanded', 'false')
  })

  it('pre-fills from initial params', () => {
    renderForm(
      <SearchForm
        initial={{
          place: 'Pune, Maharashtra', lat: 18.5204, lng: 73.8567, vehicle: 'TWO_WHEELER',
          start: new Date(2030, 0, 1, 8, 0).toISOString(), end: new Date(2030, 0, 1, 10, 30).toISOString(),
        }}
      />,
    )

    expect(screen.getByRole('combobox', { name: /where are you going/i })).toHaveValue('Pune, Maharashtra')
    expect(screen.getByLabelText('From')).toHaveValue('2030-01-01T08:00')
    expect(screen.getByLabelText('Until')).toHaveValue('2030-01-01T10:30')
    expect(screen.getByLabelText('Vehicle')).toHaveValue('TWO_WHEELER')
  })
})
