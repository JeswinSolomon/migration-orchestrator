import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import StateStepper, { StateBadge } from './StateStepper'

describe('StateStepper', () => {
  it('marks earlier steps done, the current step, and later steps upcoming', () => {
    render(<StateStepper state="CDC_SYNC" />)
    const steps = within(screen.getByRole('list', { name: 'Cutover progress' })).getAllByRole('listitem')
    expect(steps).toHaveLength(11)
    expect(steps[0]).toHaveClass('done')
    expect(steps[1]).toHaveClass('done')
    expect(steps[3]).toHaveClass('current')
    expect(steps[3]).toHaveAttribute('aria-current', 'step')
    expect(steps[4]).not.toHaveClass('done')
  })

  it('shows a rolled-back banner instead of steps', () => {
    render(<StateStepper state="ROLLED_BACK" />)
    expect(screen.getByRole('status')).toHaveTextContent('rolled back')
    expect(screen.queryByRole('list')).not.toBeInTheDocument()
  })

  it('colours badges by outcome', () => {
    render(<><StateBadge state="DONE" /><StateBadge state="ROLLED_BACK" /><StateBadge state="READY" /></>)
    expect(screen.getByText('DONE')).toHaveClass('badge-ok')
    expect(screen.getByText('ROLLED BACK')).toHaveClass('badge-bad')
    expect(screen.getByText('READY')).toHaveClass('badge-active')
  })
})
