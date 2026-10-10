import { describe, expect, it, vi } from 'vitest'
import { stepBackIfEmpty, withPageReset } from './paging'

describe('stepBackIfEmpty', () => {
  it('steps back from an empty later page', () => {
    const setPage = vi.fn()
    stepBackIfEmpty(2, setPage, [], false)
    expect(setPage).toHaveBeenCalledWith(1)
  })

  it('does nothing on the first page, with rows, while loading, or for placeholder data', () => {
    const setPage = vi.fn()
    stepBackIfEmpty(0, setPage, [], false)
    stepBackIfEmpty(2, setPage, [1], false)
    stepBackIfEmpty(2, setPage, undefined, false)
    stepBackIfEmpty(2, setPage, [], true)
    expect(setPage).not.toHaveBeenCalled()
  })
})

describe('withPageReset', () => {
  it('sets the value and goes back to the first page', () => {
    const setPage = vi.fn()
    const setValue = vi.fn()
    withPageReset(setPage)(setValue)('x')
    expect(setValue).toHaveBeenCalledWith('x')
    expect(setPage).toHaveBeenCalledWith(0)
  })
})
