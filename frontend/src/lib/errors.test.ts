import { AxiosError, AxiosHeaders } from 'axios'
import { describe, expect, it } from 'vitest'
import { errorMessage, toProblem } from './errors'

function axiosError(status: number, data: unknown) {
  const config = { headers: new AxiosHeaders() }
  return new AxiosError('failed', 'ERR_BAD_RESPONSE', config, null, {
    status,
    statusText: '',
    headers: {},
    config,
    data,
  })
}

describe('toProblem', () => {
  it('reads ProblemDetail responses', () => {
    const problem = toProblem(
      axiosError(400, { code: 'VALIDATION_FAILED', detail: 'Some fields are invalid', fieldErrors: [{ field: 'email', message: 'bad' }] }),
    )
    expect(problem).toEqual({
      status: 400,
      code: 'VALIDATION_FAILED',
      detail: 'Some fields are invalid',
      fieldErrors: [{ field: 'email', message: 'bad' }],
    })
  })

  it('explains network failures', () => {
    const err = new AxiosError('Network Error', 'ERR_NETWORK')
    expect(toProblem(err).code).toBe('NETWORK_ERROR')
    expect(errorMessage(err)).toMatch(/cannot reach the server/i)
  })

  it('handles plain errors', () => {
    expect(errorMessage(new Error('boom'))).toBe('boom')
  })
})
