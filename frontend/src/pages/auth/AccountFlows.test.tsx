import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import MockAdapter from 'axios-mock-adapter'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { toast } from 'sonner'
import { api } from '../../lib/api'
import { tokenStore } from '../../lib/tokenStore'
import { renderApp } from '../../test/renderApp'

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() }, Toaster: () => null }))

const unverified = {
  id: 3, name: 'Meera Iyer', email: 'meera@example.com', phone: null,
  role: 'DRIVER', emailVerified: false, avatarUrl: null,
}

describe('account flows', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
    mock = new MockAdapter(api)
  })

  afterEach(() => mock.restore())

  it('forgot password always shows the same confirmation', async () => {
    mock.onPost('/auth/forgot-password').reply(202)
    renderApp('/forgot-password')

    await userEvent.type(screen.getByLabelText('Email'), 'someone@example.com')
    await userEvent.click(screen.getByRole('button', { name: /send reset link/i }))

    expect(await screen.findByText(/if an account exists/i)).toBeInTheDocument()
  })

  it('verifies the email token exactly once', async () => {
    mock.onPost('/auth/verify-email').replyOnce(204)
    renderApp('/verify-email?token=abc')

    expect(await screen.findByRole('heading', { name: /email verified/i })).toBeInTheDocument()
    expect(mock.history.post.filter((r) => r.url === '/auth/verify-email')).toHaveLength(1)
  })

  it('shows a clear message for an invalid reset link', async () => {
    mock.onPost('/auth/reset-password').reply(400, { code: 'INVALID_TOKEN', detail: 'This link is invalid or has expired' })
    renderApp('/reset-password?token=old')

    await userEvent.type(screen.getByLabelText('New password'), 'newpass123')
    await userEvent.type(screen.getByLabelText('Confirm new password'), 'newpass123')
    await userEvent.click(screen.getByRole('button', { name: /reset password/i }))

    expect(await screen.findByRole('alert')).toHaveTextContent('This link is invalid or has expired')
  })

  it('shows the verification banner and updates the profile name', async () => {
    tokenStore.set('a', 'r')
    mock.onGet('/me').reply(200, unverified)
    mock.onPatch('/me').reply((config) => [200, { ...unverified, ...JSON.parse(config.data), phone: null }])
    renderApp('/account')

    expect(await screen.findByText(/verify your email/i)).toBeInTheDocument()
    const name = await screen.findByLabelText('Full name')
    await userEvent.clear(name)
    await userEvent.type(name, 'Meera R Iyer')
    await userEvent.click(screen.getByRole('button', { name: /save changes/i }))

    expect(await screen.findByDisplayValue('Meera R Iyer')).toBeInTheDocument()
    expect(JSON.parse(mock.history.patch[0].data)).toMatchObject({ name: 'Meera R Iyer' })
  })

  it('stores the fresh session returned by a password change', async () => {
    tokenStore.set('a-old', 'r-old')
    mock.onGet('/me').reply(200, unverified)
    mock.onPost('/me/password').reply(200, { accessToken: 'a-new', refreshToken: 'r-new', expiresIn: 900, user: unverified })
    renderApp('/account')

    await userEvent.type(await screen.findByLabelText('Current password'), 'oldpass123')
    await userEvent.type(screen.getByLabelText('New password'), 'newpass123')
    await userEvent.type(screen.getByLabelText('Confirm new password'), 'newpass123')
    await userEvent.click(screen.getByRole('button', { name: /change password/i }))

    await waitFor(() =>
      expect(toast.success).toHaveBeenCalledWith('Password changed. Other devices have been signed out.'),
    )
    expect(tokenStore.getAccess()).toBe('a-new')
    expect(tokenStore.getRefresh()).toBe('r-new')
  })
})
