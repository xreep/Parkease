import '@testing-library/jest-dom/vitest'

import { isAxiosError } from 'axios'
import MockAdapter from 'axios-mock-adapter'

// Every signed-in page renders the navbar bell, which asks for the unread count. Answer it with zero unless a test
// registers its own handler, so the many tests that never mention notifications don't see a 404.
const mockAdapter = MockAdapter.prototype.adapter
MockAdapter.prototype.adapter = function adapter(this: MockAdapter) {
  const handle = mockAdapter.call(this)
  return async (config) => {
    try {
      return await handle(config)
    } catch (error) {
      if (isAxiosError(error) && error.response?.status === 404 && config.method === 'get' && config.url === '/notifications/unread-count') {
        return { data: { count: 0 }, status: 200, statusText: 'OK', headers: {}, config }
      }
      throw error
    }
  }
}
