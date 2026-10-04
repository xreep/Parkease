import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import MockAdapter from 'axios-mock-adapter'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { api } from '../../lib/api'
import { renderApp } from '../../test/renderApp'

const driver = {
  id: 1, name: 'Rahul Verma', email: 'driver@example.com', phone: null,
  role: 'DRIVER', emailVerified: true, avatarUrl: null,
}

describe('LoginPage', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
    mock = new MockAdapter(api)
  })

  afterEach(() => mock.restore())

  it('validates the form before submitting', async () => {
    renderApp('/login')

    await userEvent.click(screen.getByRole('button', { name: /log in/i }))

    expect(await screen.findByText('Enter a valid email')).toBeInTheDocument()
    expect(screen.getByText('Enter your password')).toBeInTheDocument()
  })

  it('logs in and lands on the role home', async () => {
    mock.onPost('/auth/login').reply(200, { accessToken: 'a', refreshToken: 'r', expiresIn: 900, user: driver })
    renderApp('/login')

    await userEvent.type(screen.getByLabelText('Email'), 'driver@example.com')
    await userEvent.type(screen.getByLabelText('Password'), 'secret123')
    await userEvent.click(screen.getByRole('button', { name: /log in/i }))

    expect(await screen.findByRole('heading', { name: /welcome, rahul/i })).toBeInTheDocument()
  })

  it('shows the server error message', async () => {
    mock.onPost('/auth/login').reply(401, { code: 'INVALID_CREDENTIALS', detail: 'Invalid email or password' })
    renderApp('/login')

    await userEvent.type(screen.getByLabelText('Email'), 'driver@example.com')
    await userEvent.type(screen.getByLabelText('Password'), 'wrongpass1')
    await userEvent.click(screen.getByRole('button', { name: /log in/i }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid email or password')
  })
})
