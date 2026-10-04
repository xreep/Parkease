import MockAdapter from 'axios-mock-adapter'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { api } from './api'
import { uploadFile } from './uploads'

describe('uploadFile', () => {
  let mock: MockAdapter

  beforeEach(() => {
    mock = new MockAdapter(api)
  })

  afterEach(() => mock.restore())

  it('posts the file and extra fields as multipart form data', async () => {
    mock.onPost('/x').reply((config) => [
      200,
      { isForm: config.data instanceof FormData, documentType: (config.data as FormData).get('documentType') },
    ])
    const file = new File(['%PDF-1.4'], 'doc.pdf', { type: 'application/pdf' })

    const result = await uploadFile<{ isForm: boolean; documentType: string }>('/x', file, { documentType: 'PAN' })

    expect(result).toEqual({ isForm: true, documentType: 'PAN' })
  })

  it('rejects files over 5 MB without sending a request', async () => {
    const big = new File([new Uint8Array(6 * 1024 * 1024)], 'big.pdf', { type: 'application/pdf' })

    await expect(uploadFile('/x', big)).rejects.toThrow('Files must be 5 MB or smaller')
    expect(mock.history.post).toHaveLength(0)
  })
})
