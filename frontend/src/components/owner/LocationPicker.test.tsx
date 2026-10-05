import '@testing-library/jest-dom/vitest'
import { render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { LocationPicker } from './LocationPicker'

const center = { lat: 18.5204, lng: 73.8567 }

describe('LocationPicker', () => {
  it('anchors the pin tip on the chosen point', () => {
    const { container } = render(<LocationPicker value={center} center={center} onChange={() => {}} />)

    const icon = container.querySelector<HTMLElement>('.leaflet-marker-icon')
    expect(icon).not.toBeNull()
    // The 28px pin is rotated -45deg, so its tip hangs 14 + 14*sqrt(2) ~ 34px below the top of the box.
    expect(icon!.style.marginLeft).toBe('-14px')
    expect(icon!.style.marginTop).toBe('-34px')
  })

  it('lets the pin be dragged by default', () => {
    const { container } = render(<LocationPicker value={center} center={center} onChange={() => {}} />)

    expect(container.querySelector('.leaflet-marker-draggable')).not.toBeNull()
  })

  it('locks the pin when read-only', () => {
    const { container } = render(<LocationPicker value={center} center={center} onChange={() => {}} readOnly />)

    expect(container.querySelector('.leaflet-marker-icon')).not.toBeNull()
    expect(container.querySelector('.leaflet-marker-draggable')).toBeNull()
  })
})
