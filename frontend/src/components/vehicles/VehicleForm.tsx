import { useState, type FormEvent } from 'react'
import { FormError } from '../AuthCard'
import { Button } from '../ui/Button'
import { Select } from '../ui/Select'
import { TextField } from '../ui/TextField'
import { toProblem } from '../../lib/errors'
import { VEHICLE_TYPE_LABELS } from '../../lib/format'
import {
  isValidPlate,
  normalizePlate,
  PLATE_ERROR,
  useVehicleMutations,
  type VehicleDto,
  type VehicleType,
} from '../../lib/vehicles'

export type VehicleFormProps = {
  /** Present when editing: the form saves with PUT instead of POST. */
  vehicle?: VehicleDto
  /** The type preselected for a new vehicle. */
  defaultType?: VehicleType
  submitLabel: string
  /** Offer the "Use as default" checkbox (not needed for a driver's first vehicle). */
  showDefault?: boolean
  onSaved: (vehicle: VehicleDto) => void
  onCancel?: () => void
}

/** Add or edit a vehicle: validates the plate like the server before sending anything. */
export function VehicleForm({ vehicle, defaultType = 'FOUR_WHEELER', submitLabel, showDefault = true, onSaved, onCancel }: VehicleFormProps) {
  const { create, update } = useVehicleMutations()
  const [type, setType] = useState<VehicleType>(vehicle?.type ?? defaultType)
  const [plate, setPlate] = useState(vehicle?.plateNumber ?? '')
  const [makeModel, setMakeModel] = useState(vehicle?.makeModel ?? '')
  const [isDefault, setIsDefault] = useState(false)
  const [plateError, setPlateError] = useState<string | undefined>()
  const [formError, setFormError] = useState<string | null>(null)
  const busy = create.isPending || update.isPending

  async function submit(e: FormEvent) {
    e.preventDefault()
    setFormError(null)
    const plateNumber = normalizePlate(plate)
    if (!isValidPlate(plateNumber)) {
      setPlateError(PLATE_ERROR)
      return
    }
    setPlateError(undefined)
    const body = { type, plateNumber, makeModel: makeModel.trim() || undefined, isDefault: showDefault && !vehicle ? isDefault : undefined }
    try {
      const saved = vehicle ? await update.mutateAsync({ id: vehicle.id, body }) : await create.mutateAsync(body)
      if (!vehicle) {
        setPlate('')
        setMakeModel('')
        setIsDefault(false)
      }
      onSaved(saved)
    } catch (error) {
      const problem = toProblem(error)
      if (problem.code === 'PLATE_TAKEN') setPlateError("You've already added this vehicle")
      else if (problem.code === 'INVALID_PLATE') setPlateError(PLATE_ERROR)
      else setFormError(problem.detail)
    }
  }

  return (
    <form onSubmit={(e) => void submit(e)} noValidate className="space-y-4">
      <FormError message={formError} />
      <Select label="Vehicle type" value={type} onChange={(e) => setType(e.target.value as VehicleType)}>
        <option value="FOUR_WHEELER">{VEHICLE_TYPE_LABELS.FOUR_WHEELER}</option>
        <option value="TWO_WHEELER">{VEHICLE_TYPE_LABELS.TWO_WHEELER}</option>
      </Select>
      <TextField
        label="Number plate"
        placeholder="MH 12 AB 1234"
        autoComplete="off"
        autoCapitalize="characters"
        value={plate}
        error={plateError}
        onChange={(e) => {
          setPlate(e.target.value)
          setPlateError(undefined)
        }}
      />
      <TextField
        label="Make and model (optional)"
        maxLength={60}
        value={makeModel}
        onChange={(e) => setMakeModel(e.target.value)}
      />
      {showDefault && !vehicle && (
        <label className="flex items-center gap-2 text-sm text-slate-700 dark:text-slate-300">
          <input
            type="checkbox"
            checked={isDefault}
            onChange={(e) => setIsDefault(e.target.checked)}
            className="h-4 w-4 rounded border-slate-300 text-brand-600 focus:ring-brand-500"
          />
          Use as default
        </label>
      )}
      <div className="flex flex-wrap justify-end gap-2">
        {onCancel && (
          <Button type="button" variant="secondary" disabled={busy} onClick={onCancel}>Cancel</Button>
        )}
        <Button type="submit" loading={busy}>{submitLabel}</Button>
      </div>
    </form>
  )
}
