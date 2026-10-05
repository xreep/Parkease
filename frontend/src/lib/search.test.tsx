import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderHook, waitFor } from '@testing-library/react'
import MockAdapter from 'axios-mock-adapter'
import type { ReactNode } from 'react'
import { describe, expect, it } from 'vitest'
import { api } from './api'
import { parseSearchParams, toApiQuery, toSearchParams, useSearch, type SearchParams } from './search'

describe('search params', () => {
  const full: SearchParams = {
    place: 'Pune, Maharashtra',
    lat: 18.5204,
    lng: 73.8567,
    start: '2026-10-06T04:30:00.000Z',
    end: '2026-10-06T06:30:00.000Z',
    vehicle: 'TWO_WHEELER',
    radius: 10,
    types: ['METRO', 'OFFICE'],
    amenities: ['COVERED', 'CCTV'],
    maxPrice: 80,
    open24x7: true,
    sort: 'price',
    page: 2,
  }

  it('round-trips every field, repeating keys for arrays', () => {
    const usp = toSearchParams(full)
    expect(usp.getAll('types')).toEqual(['METRO', 'OFFICE'])
    expect(usp.getAll('amenities')).toEqual(['COVERED', 'CCTV'])
    expect(parseSearchParams(new URLSearchParams(usp.toString()))).toEqual(full)
  })

  it('omits empty and default values', () => {
    const usp = toSearchParams({
      place: 'Pune', lat: 18.5, lng: 73.8, radius: 5, types: [], amenities: [], open24x7: false, sort: 'distance', page: 0,
    })
    expect(usp.toString()).toBe('place=Pune&lat=18.5&lng=73.8')
  })

  it('only writes a positive maxPrice', () => {
    const base = { place: 'X', lat: 1, lng: 2 }
    expect(toSearchParams({ ...base, maxPrice: 0 }).has('maxPrice')).toBe(false)
    expect(toSearchParams({ ...base, maxPrice: -5 }).has('maxPrice')).toBe(false)
    expect(toApiQuery({ ...base, maxPrice: 0 })).toEqual({ lat: 1, lng: 2 })
    expect(parseSearchParams(toSearchParams({ ...base, maxPrice: 0 }))).toEqual(base)
  })

  it('applies defaults when parsing a minimal query', () => {
    expect(parseSearchParams(new URLSearchParams('place=Pune&lat=18.5&lng=73.8'))).toEqual({
      place: 'Pune', lat: 18.5, lng: 73.8,
    })
  })

  it('returns null for missing or invalid coordinates', () => {
    expect(parseSearchParams(new URLSearchParams('place=Pune'))).toBeNull()
    expect(parseSearchParams(new URLSearchParams('place=Pune&lat=abc&lng=73.8'))).toBeNull()
    expect(parseSearchParams(new URLSearchParams('place=Pune&lat=18.5&lng='))).toBeNull()
    expect(parseSearchParams(new URLSearchParams('lat=95&lng=73.8'))).toBeNull()
  })

  it('ignores unknown enum values', () => {
    const p = parseSearchParams(
      new URLSearchParams('place=X&lat=1&lng=2&vehicle=BUS&types=METRO&types=NOPE&amenities=WIFI&sort=magic&page=-3'),
    )
    expect(p).toEqual({ place: 'X', lat: 1, lng: 2, types: ['METRO'] })
  })

  it('maps to API query names', () => {
    expect(toApiQuery(full)).toEqual({
      lat: 18.5204,
      lng: 73.8567,
      start: '2026-10-06T04:30:00.000Z',
      end: '2026-10-06T06:30:00.000Z',
      vehicleType: 'TWO_WHEELER',
      radiusKm: 10,
      types: ['METRO', 'OFFICE'],
      amenities: ['COVERED', 'CCTV'],
      maxPricePerHour: 80,
      open24x7: true,
      sort: 'price',
      page: 2,
    })
    expect(toApiQuery({ place: 'X', lat: 1, lng: 2 })).toEqual({ lat: 1, lng: 2 })
  })
})

describe('useSearch', () => {
  it('sends repeated keys for array filters, as Spring expects', async () => {
    const mock = new MockAdapter(api)
    mock.onGet('/search').reply(200, { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        {children}
      </QueryClientProvider>
    )

    const { result } = renderHook(
      () => useSearch({ place: 'X', lat: 1, lng: 2, types: ['OFFICE', 'METRO'], amenities: ['CCTV', 'COVERED'] }),
      { wrapper },
    )
    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    const uri = decodeURIComponent(api.getUri(mock.history.get[0]))
    expect(uri).toContain('types=OFFICE&types=METRO')
    expect(uri).toContain('amenities=CCTV&amenities=COVERED')
    expect(uri).not.toContain('[]')
    mock.restore()
  })
})
