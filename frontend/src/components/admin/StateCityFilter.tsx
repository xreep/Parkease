import { Select } from '../ui/Select'
import { useStateDetail, useStates } from '../../lib/locations'

export type StateCity = { stateId?: number; cityId?: number }

/** A state select and, once a state is picked, a city select of that state. Clearing the state clears the city. */
export function StateCityFilter({ value, onChange }: { value: StateCity; onChange: (value: StateCity) => void }) {
  const states = useStates()
  const state = states.data?.find((s) => s.id === value.stateId)
  const detail = useStateDetail(state?.slug ?? '')
  const loadingCities = state !== undefined && detail.data === undefined && !detail.error

  return (
    <>
      <Select
        label="State"
        value={value.stateId ?? ''}
        onChange={(e) => onChange(e.target.value === '' ? {} : { stateId: Number(e.target.value) })}
      >
        <option value="">All states</option>
        {states.data?.map((s) => (
          <option key={s.id} value={s.id}>{s.name}</option>
        ))}
      </Select>
      <Select
        label="City"
        value={value.cityId ?? ''}
        disabled={state === undefined || loadingCities}
        onChange={(e) => onChange({ stateId: value.stateId, ...(e.target.value !== '' && { cityId: Number(e.target.value) }) })}
      >
        <option value="">{loadingCities ? 'Loading cities…' : 'All cities'}</option>
        {detail.data?.cities.map((c) => (
          <option key={c.id} value={c.id}>{c.name}</option>
        ))}
      </Select>
    </>
  )
}
