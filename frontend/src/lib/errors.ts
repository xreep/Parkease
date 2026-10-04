import { isAxiosError } from 'axios'

export type FieldError = { field: string; message: string }
export type Problem = { status: number; code: string; detail: string; fieldErrors: FieldError[] }

type ProblemBody = { code?: string; detail?: string; title?: string; fieldErrors?: FieldError[] }

export function toProblem(error: unknown): Problem {
  if (isAxiosError(error)) {
    if (!error.response) {
      return {
        status: 0,
        code: 'NETWORK_ERROR',
        detail: 'Cannot reach the server. Check your connection and try again.',
        fieldErrors: [],
      }
    }
    const body = (error.response.data ?? {}) as ProblemBody
    return {
      status: error.response.status,
      code: body.code ?? 'UNKNOWN',
      detail: body.detail ?? body.title ?? 'Something went wrong',
      fieldErrors: Array.isArray(body.fieldErrors) ? body.fieldErrors : [],
    }
  }
  return {
    status: 0,
    code: 'UNKNOWN',
    detail: error instanceof Error ? error.message : 'Something went wrong',
    fieldErrors: [],
  }
}

export function errorMessage(error: unknown): string {
  return toProblem(error).detail
}
