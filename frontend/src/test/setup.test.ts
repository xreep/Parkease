import MockAdapter from 'axios-mock-adapter'
import { afterEach, describe, expect, it } from 'vitest'
import { api } from '../lib/api'

describe('shared test setup', () => {
  let mock: MockAdapter
  afterEach(() => mock.restore())

  it('answers the navbar bell\'s unread-count request unless a test says otherwise', async () => {
    mock = new MockAdapter(api)
    expect((await api.get('/notifications/unread-count')).data).toEqual({ count: 0 })

    mock.onGet('/notifications/unread-count').reply(200, { count: 4 })
    expect((await api.get('/notifications/unread-count')).data).toEqual({ count: 4 })
  })
})
