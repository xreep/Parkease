import MockAdapter from 'axios-mock-adapter'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from './api'
import { downloadCsv } from './download'

describe('downloadCsv', () => {
  let mock: MockAdapter
  let downloads: { download: string; href: string }[]
  const original = { create: URL.createObjectURL, revoke: URL.revokeObjectURL }

  beforeEach(() => {
    mock = new MockAdapter(api)
    Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:csv'), revokeObjectURL: vi.fn() })
    downloads = []
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      downloads.push({ download: this.download, href: this.href })
    })
  })

  afterEach(() => {
    mock.restore()
    vi.restoreAllMocks()
    Object.assign(URL, { createObjectURL: original.create, revokeObjectURL: original.revoke })
  })

  it('asks for a csv blob with the params and saves it under the server filename', async () => {
    mock.onGet('/x').reply(200, new Blob(['a']), { 'content-disposition': 'attachment; filename="parkease-x.csv"' })

    const result = await downloadCsv('/x', { from: '2026-10-01' }, 'fallback.csv')

    expect(result).toEqual({ truncated: false })
    expect(downloads).toEqual([{ download: 'parkease-x.csv', href: 'blob:csv' }])
    expect(mock.history.get[0].params).toEqual({ from: '2026-10-01', format: 'csv' })
    expect(mock.history.get[0].responseType).toBe('blob')
  })

  it('falls back to the given name and reports a truncated export', async () => {
    mock.onGet('/x').reply(200, new Blob(['a']), { 'x-truncated': 'true' })

    expect(await downloadCsv('/x', {}, 'fallback.csv')).toEqual({ truncated: true })
    expect(downloads[0].download).toBe('fallback.csv')
  })

  it('throws the problem detail of a failed export', async () => {
    mock.onGet('/x').reply(400, new Blob([JSON.stringify({ code: 'INVALID_DATE_RANGE', detail: 'Range too long' })]))

    await expect(downloadCsv('/x', {}, 'f.csv')).rejects.toThrow('Range too long')
    expect(downloads).toHaveLength(0)
  })
})
