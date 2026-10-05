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
    vi.restoreAllMocks()
  })

  /** Pretends the browser is in the given IANA time zone. */
  function browserZone(timeZone: string) {
    const real = Intl.DateTimeFormat.prototype.resolvedOptions
    vi.spyOn(Intl.DateTimeFormat.prototype, 'resolvedOptions').mockImplementation(function (this: Intl.DateTimeFormat) {
      return { ...real.call(this), timeZone }
    })
  }

  async function pickPune(user: ReturnType<typeof userEvent.setup>) {
    await user.type(screen.getByRole('combobox', { name: /where are you going/i }), 'pun')
    await user.click(await screen.findByRole('option', { name: 'Pune, Maharashtra' }))
  }

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

  it('never calls Nominatim while typing, only after the user chooses to search places', async () => {
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
    const action = await screen.findByRole('option', { name: 'Search places for \u201Candheri metro\u201D' })
    expect(fetchMock).not.toHaveBeenCalled()

    await user.click(action)

    expect(await screen.findByRole('option', { name: 'Andheri Metro Station, Andheri East, Mumbai' })).toBeInTheDocument()
    expect(screen.getByText('Places')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(String(fetchMock.mock.calls[0][0])).toBe(
      'https://nominatim.openstreetmap.org/search?format=json&limit=5&countrycodes=in&q=andheri%20metro',
    )
    expect(screen.queryByRole('option', { name: /search places for/i })).not.toBeInTheDocument()
  })

  it('picks a looked-up place and searches with it', async () => {
    mock.onGet('/cities').reply(200, [])
    fetchMock.mockResolvedValue({
      ok: true,
      json: async () => [{ display_name: 'Andheri Metro Station, Andheri East, Mumbai, India', lat: '19.1197', lon: '72.8464' }],
    })
    const user = userEvent.setup()
    renderForm()

    await user.type(screen.getByRole('combobox', { name: /where are you going/i }), 'andheri')
    await user.click(await screen.findByRole('option', { name: /search places for/i }))
    await user.click(await screen.findByRole('option', { name: 'Andheri Metro Station, Andheri East, Mumbai' }))
    await user.click(screen.getByRole('button', { name: /search parking/i }))

    const search = (await screen.findByTestId('location')).textContent!
    expect(search).toContain('place=Andheri+Metro+Station%2C+Andheri+East%2C+Mumbai&lat=19.1197&lng=72.8464')
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('does not offer the places lookup for fewer than 3 characters', async () => {
    mock.onGet('/cities').reply(200, [])
    const user = userEvent.setup()
    renderForm()

    await user.type(screen.getByRole('combobox', { name: /where are you going/i }), 'an')
    await screen.findByText('No matches')

    expect(screen.queryByRole('option', { name: /search places for/i })).not.toBeInTheDocument()
  })

  it('does not repeat the lookup on further typing and offers it again for the new text', async () => {
    mock.onGet('/cities').reply(200, [])
    const user = userEvent.setup()
    renderForm()

    const input = screen.getByRole('combobox', { name: /where are you going/i })
    await user.type(input, 'andheri')
    await user.click(await screen.findByRole('option', { name: /search places for/i }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))

    await user.type(input, ' east')

    expect(await screen.findByRole('option', { name: 'Search places for \u201Candheri east\u201D' })).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('lists cities first, then the places action, and supports the keyboard', async () => {
    mock.onGet('/cities').reply(200, [pune])
    fetchMock.mockResolvedValue({
      ok: true,
      json: async () => [{ display_name: 'Pune Railway Station, Camp, Pune, India', lat: '18.528', lon: '73.874' }],
    })
    const user = userEvent.setup()
    renderForm()

    const input = screen.getByRole('combobox', { name: /where are you going/i })
    await user.type(input, 'pun')
    const listbox = await screen.findByRole('listbox')
    await waitFor(() => expect(within(listbox).getAllByRole('option')).toHaveLength(2))
    const options = within(listbox).getAllByRole('option')
    expect(options.map((o) => o.textContent)).toEqual(['Pune, Maharashtra', 'Search places for \u201Cpun\u201D'])
    expect(screen.getByText('Cities')).toBeInTheDocument()
    expect(input).toHaveAttribute('aria-expanded', 'true')
    expect(fetchMock).not.toHaveBeenCalled()

    // Enter on the action row runs the lookup once and keeps the list open with the results.
    await user.keyboard('{ArrowDown}{ArrowDown}')
    expect(input).toHaveAttribute('aria-activedescendant', options[1].id)
    await user.keyboard('{Enter}')
    await waitFor(() => expect(within(listbox).getAllByRole('option').map((o) => o.textContent)).toEqual([
      'Pune, Maharashtra',
      'Pune Railway Station, Camp, Pune',
    ]))
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(input).toHaveAttribute('aria-expanded', 'true')

    await user.keyboard('{ArrowDown}{ArrowDown}{Enter}')

    expect(input).toHaveValue('Pune Railway Station, Camp, Pune')
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

  it("rejects a start before the current quarter hour", async () => {
    mock.onGet('/cities').reply(200, [pune])
    const user = userEvent.setup()
    renderForm()

    await user.type(screen.getByRole('combobox', { name: /where are you going/i }), 'pun')
    await user.click(await screen.findByRole('option', { name: 'Pune, Maharashtra' }))
    const from = screen.getByLabelText('From')
    const until = screen.getByLabelText('Until')
    await user.clear(from)
    await user.type(from, '2020-01-01T08:00')
    await user.clear(until)
    await user.type(until, '2020-01-01T10:00')
    await user.click(screen.getByRole('button', { name: /search parking/i }))

    expect(await screen.findByText("Start time can't be in the past")).toBeInTheDocument()
    expect(screen.queryByTestId('location')).not.toBeInTheDocument()
  })

  it('does not call Nominatim for queries shorter than 3 characters', async () => {
    mock.onGet('/cities').reply(200, [])
    const user = userEvent.setup()
    renderForm()

    await user.type(screen.getByRole('combobox', { name: /where are you going/i }), 'pu')

    expect(await screen.findByText('No matches')).toBeInTheDocument()
    expect(mock.history.get.filter((r) => r.url === '/cities')).not.toHaveLength(0)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('keeps the listbox to options and groups, with status text outside it', async () => {
    mock.onGet('/cities').reply(200, [pune])
    fetchMock.mockResolvedValue({
      ok: true,
      json: async () => [{ display_name: 'Pune Railway Station, Camp, Pune, India', lat: '18.528', lon: '73.874' }],
    })
    const user = userEvent.setup()
    renderForm()

    await user.type(screen.getByRole('combobox', { name: /where are you going/i }), 'pun')
    await user.click(await screen.findByRole('option', { name: /search places for/i }))
    const listbox = await screen.findByRole('listbox')
    await waitFor(() => expect(within(listbox).getAllByRole('option')).toHaveLength(2))

    const groups = within(listbox).getAllByRole('group')
    expect(groups.map((g) => g.getAttribute('aria-labelledby')).map((id) => document.getElementById(id!)?.textContent)).toEqual([
      'Cities',
      'Places',
    ])
    for (const child of Array.from(listbox.children)) expect(['group', 'option']).toContain(child.getAttribute('role'))
    expect(within(listbox).queryByRole('status')).not.toBeInTheDocument()
  })

  it('shows Searching… in a status region and drops stale options while a new lookup is pending', async () => {
    mock.onGet('/cities').replyOnce(200, [pune])
    const user = userEvent.setup()
    renderForm()

    const input = screen.getByRole('combobox', { name: /where are you going/i })
    await user.type(input, 'pun')
    await screen.findByRole('option', { name: 'Pune, Maharashtra' })

    mock.onGet('/cities').reply(() => new Promise(() => {}))
    await user.type(input, 'e')

    const listbox = screen.getByRole('listbox', { hidden: true })
    expect(within(listbox).queryAllByRole('option', { hidden: true })).toHaveLength(0)
    expect(screen.getByRole('status')).toHaveTextContent('Searching…')
    expect(listbox).not.toContainElement(screen.getByRole('status'))
  })

  it('rejects times that are not on 15-minute steps', async () => {
    mock.onGet('/cities').reply(200, [pune])
    const user = userEvent.setup()
    renderForm()

    await pickPune(user)
    const from = screen.getByLabelText('From')
    await user.clear(from)
    await user.type(from, '2030-01-01T08:10')
    const until = screen.getByLabelText('Until')
    await user.clear(until)
    await user.type(until, '2030-01-01T10:20')
    await user.click(screen.getByRole('button', { name: /search parking/i }))

    expect(await screen.findAllByText('Use 15-minute steps (e.g. 10:00, 10:15)')).toHaveLength(2)
    expect(screen.queryByTestId('location')).not.toBeInTheDocument()
  })

  it('rejects an end that is not on a 15-minute step', async () => {
    mock.onGet('/cities').reply(200, [pune])
    const user = userEvent.setup()
    renderForm()

    await pickPune(user)
    const from = screen.getByLabelText('From')
    await user.clear(from)
    await user.type(from, '2030-01-01T08:00')
    const until = screen.getByLabelText('Until')
    await user.clear(until)
    await user.type(until, '2030-01-01T10:20')
    await user.click(screen.getByRole('button', { name: /search parking/i }))

    expect(await screen.findByText('Use 15-minute steps (e.g. 10:00, 10:15)')).toBeInTheDocument()
  })

  it('rejects a booking longer than 90 days and accepts exactly 90', async () => {
    mock.onGet('/cities').reply(200, [pune])
    const user = userEvent.setup()
    renderForm()

    await pickPune(user)
    const from = screen.getByLabelText('From')
    await user.clear(from)
    await user.type(from, '2030-01-01T08:00')
    const until = screen.getByLabelText('Until')
    await user.clear(until)
    await user.type(until, '2030-04-02T08:00')
    await user.click(screen.getByRole('button', { name: /search parking/i }))

    expect(await screen.findByText('Bookings can be at most 90 days')).toBeInTheDocument()
    expect(screen.queryByTestId('location')).not.toBeInTheDocument()

    await user.clear(until)
    await user.type(until, '2030-04-01T08:00')
    await user.click(screen.getByRole('button', { name: /search parking/i }))

    expect(await screen.findByTestId('location')).toBeInTheDocument()
  })

  it('says times are in IST when the browser is in another time zone', () => {
    browserZone('America/New_York')
    renderForm()

    expect(screen.getByText('Times use your device’s time zone. Opening hours are shown in IST.')).toBeInTheDocument()
  })

  it('shows no time zone hint when the browser is already on IST', () => {
    browserZone('Asia/Kolkata')
    renderForm()

    expect(screen.queryByText(/Opening hours are shown in IST/)).not.toBeInTheDocument()
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
