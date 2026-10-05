import { useQuery } from '@tanstack/react-query'
import { api } from './api'

export type StateType = 'STATE' | 'UT'

export type StateSummary = {
  id: number
  name: string
  code: string
  slug: string
  type: StateType
  capitalName: string
  cityCount: number
}

export type City = {
  id: number
  name: string
  slug: string
  lat: number
  lng: number
  capital: boolean
  stateName: string
  stateCode: string
  stateSlug: string
}

export type StateDetail = Omit<StateSummary, 'cityCount'> & { cities: City[] }

export function useStates() {
  return useQuery({
    queryKey: ['states'],
    queryFn: async () => (await api.get<StateSummary[]>('/states')).data,
    staleTime: Infinity,
  })
}

export function useStateDetail(slug: string) {
  return useQuery({
    queryKey: ['state', slug],
    queryFn: async () => (await api.get<StateDetail>(`/states/${slug}`)).data,
    enabled: slug !== '',
    staleTime: Infinity,
  })
}

export function useCity(stateSlug: string, citySlug: string) {
  return useQuery({
    queryKey: ['city', stateSlug, citySlug],
    queryFn: async () => (await api.get<City>(`/states/${stateSlug}/cities/${citySlug}`)).data,
    enabled: stateSlug !== '' && citySlug !== '',
    staleTime: Infinity,
  })
}
