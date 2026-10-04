import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { Dialog, ReasonDialog } from './Dialog'
import { Select } from './Select'
import { StatusBadge } from './StatusBadge'
import { Tabs } from './Tabs'
import { TextArea } from './TextArea'

describe('shared UI', () => {
  it('renders status badges with human labels', () => {
    render(<StatusBadge kind="listing" status="PENDING_REVIEW" />)
    expect(screen.getByText('Pending review')).toBeInTheDocument()
  })

  it('links labels and errors on Select and TextArea', () => {
    render(
      <>
        <Select label="Type" error="Pick one"><option value="a">A</option></Select>
        <TextArea label="Notes" hint="Optional" />
      </>,
    )
    expect(screen.getByLabelText('Type')).toHaveAccessibleDescription('Pick one')
    expect(screen.getByLabelText('Notes')).toHaveAttribute('rows', '4')
  })

  it('renders an accessible dialog that closes on Escape', async () => {
    const onClose = vi.fn()
    render(<Dialog open title="Hello" onClose={onClose}><button>Inside</button></Dialog>)
    expect(screen.getByRole('dialog', { name: 'Hello' })).toHaveAttribute('aria-modal', 'true')
    await userEvent.keyboard('{Escape}')
    expect(onClose).toHaveBeenCalled()
  })

  it('renders nothing when the dialog is closed', () => {
    render(<Dialog open={false} title="Hello" onClose={() => {}}>x</Dialog>)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('ReasonDialog requires a reason and submits it', async () => {
    const onConfirm = vi.fn()
    const onClose = vi.fn()
    render(<ReasonDialog open title="Reject" confirmLabel="Reject it" onConfirm={onConfirm} onClose={onClose} />)

    await userEvent.click(screen.getByRole('button', { name: 'Reject it' }))
    expect(await screen.findByText('Please give a reason')).toBeInTheDocument()
    expect(onConfirm).not.toHaveBeenCalled()

    await userEvent.type(screen.getByLabelText('Reason'), 'Blurry')
    await userEvent.click(screen.getByRole('button', { name: 'Reject it' }))
    expect(onConfirm).toHaveBeenCalledWith('Blurry')

    await userEvent.keyboard('{Escape}')
    expect(onClose).toHaveBeenCalled()
  })

  it('renders tabs as links', () => {
    render(
      <MemoryRouter initialEntries={['/a']}>
        <Tabs items={[{ to: '/a', label: 'A', end: true }, { to: '/b', label: 'B' }]} />
      </MemoryRouter>,
    )
    expect(screen.getByRole('link', { name: 'A' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('link', { name: 'B' })).toHaveAttribute('href', '/b')
  })

  it('moves focus into the dialog and returns it to the trigger on close', async () => {
    function Harness() {
      const [open, setOpen] = useState(false)
      return (
        <>
          <button onClick={() => setOpen(true)}>Open it</button>
          <Dialog open={open} title="Focus" onClose={() => setOpen(false)}><button>Inside</button></Dialog>
        </>
      )
    }
    render(<Harness />)
    const trigger = screen.getByRole('button', { name: 'Open it' })
    await userEvent.click(trigger)
    expect(screen.getByRole('dialog')).toContainElement(document.activeElement as HTMLElement)

    await userEvent.keyboard('{Escape}')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(trigger).toHaveFocus()
  })

  it('keeps the reason dialog open and shows the error when confirming fails', async () => {
    const onConfirm = vi.fn().mockRejectedValue(new Error('Server said no'))
    const onClose = vi.fn()
    render(<ReasonDialog open title="Reject" confirmLabel="Reject it" onConfirm={onConfirm} onClose={onClose} />)

    await userEvent.type(screen.getByLabelText('Reason'), 'Blurry')
    await userEvent.click(screen.getByRole('button', { name: 'Reject it' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Server said no')
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(onClose).not.toHaveBeenCalled()
  })

  it('ignores backdrop clicks while the reason is being submitted', async () => {
    let finish: () => void = () => {}
    const onConfirm = vi.fn(() => new Promise<void>((resolve) => { finish = resolve }))
    const onClose = vi.fn()
    render(<ReasonDialog open title="Reject" confirmLabel="Reject it" onConfirm={onConfirm} onClose={onClose} />)

    await userEvent.type(screen.getByLabelText('Reason'), 'Blurry')
    await userEvent.click(screen.getByRole('button', { name: 'Reject it' }))
    const backdrop = screen.getByRole('dialog').parentElement as HTMLElement
    await userEvent.pointer({ keys: '[MouseLeft]', target: backdrop })
    expect(onClose).not.toHaveBeenCalled()

    finish()
    await waitFor(() => expect(screen.getByRole('button', { name: 'Reject it' })).toBeEnabled())
    await userEvent.pointer({ keys: '[MouseLeft]', target: backdrop })
    expect(onClose).toHaveBeenCalled()
  })
})
