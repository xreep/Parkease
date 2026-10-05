import { DEFAULT_RADIUS_KM, type Amenity, type ListingType, type SearchParams } from './search'

export const RADIUS_OPTIONS = [1, 2, 5, 10, 25] as const

export type Filters = {
  radius: number
  maxPrice?: number
  types: ListingType[]
  amenities: Amenity[]
  open24x7: boolean
}

export const NO_FILTERS: Filters = { radius: DEFAULT_RADIUS_KM, types: [], amenities: [], open24x7: false }

/** How many filters differ from the defaults (the distance counts when it is not 5 km). */
export function activeFilterCount(f: Filters): number {
  return (
    (f.radius !== DEFAULT_RADIUS_KM ? 1 : 0) +
    (f.maxPrice !== undefined ? 1 : 0) +
    f.types.length +
    f.amenities.length +
    (f.open24x7 ? 1 : 0)
  )
}

export function filtersOf(p: SearchParams): Filters {
  return {
    radius: p.radius ?? DEFAULT_RADIUS_KM,
    maxPrice: p.maxPrice,
    types: p.types ?? [],
    amenities: p.amenities ?? [],
    open24x7: p.open24x7 ?? false,
  }
}

/** Filter values as URL params: defaults and empty lists become undefined so they stay out of the URL. */
export function paramsOf(f: Filters): Partial<SearchParams> {
  return {
    radius: f.radius === DEFAULT_RADIUS_KM ? undefined : f.radius,
    maxPrice: f.maxPrice,
    types: f.types.length ? f.types : undefined,
    amenities: f.amenities.length ? f.amenities : undefined,
    open24x7: f.open24x7 || undefined,
  }
}
