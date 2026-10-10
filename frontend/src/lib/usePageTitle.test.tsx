import { render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { usePageTitle } from './usePageTitle'

function Probe({ title }: { title?: string | null }) {
  usePageTitle(title)
  return null
}

describe('usePageTitle', () => {
  it('sets "ParkEase — <page>"', () => {
    render(<Probe title="Search parking" />)
    expect(document.title).toBe('ParkEase — Search parking')
  })

  it('follows the title when it changes (e.g. once data has loaded)', () => {
    const { rerender } = render(<Probe title="Parking spot" />)
    expect(document.title).toBe('ParkEase — Parking spot')
    rerender(<Probe title="Metro Hub Parking" />)
    expect(document.title).toBe('ParkEase — Metro Hub Parking')
  })

  it('leaves the title alone when given none, so a nested page can set its own', () => {
    document.title = 'ParkEase — Page not found'
    const { rerender } = render(<Probe title={undefined} />)
    rerender(<Probe title="" />)
    expect(document.title).toBe('ParkEase — Page not found')
  })
})
