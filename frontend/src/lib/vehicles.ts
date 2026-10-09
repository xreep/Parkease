import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './api'
import type { VehicleType } from './owner'

export type { VehicleType }

export type VehicleDto = {
  id: number
  type: VehicleType
  plateNumber: string
  makeModel: string | null
  isDefault: boolean
}

export type VehicleBody = { type: VehicleType; plateNumber: string; makeModel?: string; isDefault?: boolean }

/** State series, e.g. MH12AB1234, DL3CAB1234, KA011234 (mirrors the server's PlateNumbers). */
const STANDARD = /^[A-Z]{2}[0-9]{1,2}[A-Z]{0,3}[0-9]{4}$/
/** Bharat series, e.g. 22BH1234AA. */
const BHARAT = /^[0-9]{2}BH[0-9]{4}[A-Z]{1,2}$/

/** Upper-cases and strips spaces, hyphens and dots. */
export function normalizePlate(raw: string): string {
  return raw.replace(/[\s\-.]/g, '').toUpperCase()
}

/** True when an already-normalised plate matches one of the accepted Indian formats. */
export function isValidPlate(normalized: string): boolean {
  return STANDARD.test(normalized) || BHARAT.test(normalized)
}

export const PLATE_ERROR = 'Enter a valid Indian number plate'

export const listVehicles = async () => (await api.get<VehicleDto[]>('/me/vehicles')).data
export const createVehicle = async (body: VehicleBody) => (await api.post<VehicleDto>('/me/vehicles', body)).data
export const updateVehicle = async (id: number, body: VehicleBody) =>
  (await api.put<VehicleDto>(`/me/vehicles/${id}`, body)).data
export const deleteVehicle = async (id: number) => {
  await api.delete(`/me/vehicles/${id}`)
}

/** `enabled` is false for visitors who are not drivers: the endpoint is driver-only. */
export function useVehicles(enabled = true) {
  return useQuery({ queryKey: ['vehicles'], queryFn: listVehicles, enabled })
}

/** Refetches the vehicle list once a mutation succeeds. */
export function useVehicleMutations() {
  const queryClient = useQueryClient()
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['vehicles'] })
  return {
    create: useMutation({ mutationFn: createVehicle, onSuccess: refresh }),
    update: useMutation({ mutationFn: ({ id, body }: { id: number; body: VehicleBody }) => updateVehicle(id, body), onSuccess: refresh }),
    remove: useMutation({ mutationFn: deleteVehicle, onSuccess: refresh }),
  }
}
