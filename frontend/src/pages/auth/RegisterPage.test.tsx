import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import MockAdapter from 'axios-mock-adapter'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { api } from '../../lib/api'
import { renderApp } from '../../test/renderApp'

describe('RegisterPage', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
    mock = new MockAdapter(api)
  })

  afterEach(() => mock.restore())

  async function fill(password = 'secret123', confirm = 'secret123') {
    await userEvent.type(screen.getByLabelText('Full name'), 'Priya Sharma')
    await userEvent.type(screen.getByLabelText('Email'), 'priya@example.com')
    await userEvent.type(screen.getByLabelText('Password'), password)
    await userEvent.type(screen.getByLabelText('Confirm password'), confirm)
  }

  it('rejects mismatched passwords', async () => {
    renderApp('/register')
    await fill('secret123', 'secret999')

    await userEvent.click(screen.getByRole('button', { name: /create account/i }))

    expect(await screen.findByText('Passwords do not match')).toBeInTheDocument()
  })

  it('registers an owner chosen via ?role=OWNER and shows server field errors', async () => {
    mock.onPost('/auth/register').reply((config) => {
      const body = JSON.parse(config.data)
      expect(body.role).toBe('OWNER')
      return [409, { code: 'EMAIL_TAKEN', detail: 'An account with this email already exists' }]
    })
    renderApp('/register?role=OWNER')
    await fill()

    await userEvent.click(screen.getByRole('button', { name: /create account/i }))

    expect(await screen.findByRole('alert')).toHaveTextContent('An account with this email already exists')
  })
})
