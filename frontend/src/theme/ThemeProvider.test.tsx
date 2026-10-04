import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { ThemeProvider, useTheme } from './ThemeProvider'

function Probe() {
  const { theme, toggle } = useTheme()
  return <button onClick={toggle}>theme:{theme}</button>
}

describe('ThemeProvider', () => {
  beforeEach(() => {
    localStorage.clear()
    document.documentElement.classList.remove('dark')
  })

  it('toggles the dark class and remembers the choice', async () => {
    render(<ThemeProvider><Probe /></ThemeProvider>)
    expect(screen.getByRole('button')).toHaveTextContent('theme:light')

    await userEvent.click(screen.getByRole('button'))

    expect(screen.getByRole('button')).toHaveTextContent('theme:dark')
    expect(document.documentElement).toHaveClass('dark')
    expect(localStorage.getItem('sp_theme')).toBe('dark')
  })
})
