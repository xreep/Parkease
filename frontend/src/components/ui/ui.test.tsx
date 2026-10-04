import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { Button } from './Button'
import { TextField } from './TextField'

describe('UI primitives', () => {
  it('disables a loading button', () => {
    render(<Button loading>Save</Button>)
    expect(screen.getByRole('button', { name: /save/i })).toBeDisabled()
  })

  it('links the text field label and shows errors accessibly', () => {
    render(<TextField label="Email" error="Enter a valid email" />)
    const input = screen.getByLabelText('Email')
    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(input).toHaveAccessibleDescription('Enter a valid email')
  })
})
