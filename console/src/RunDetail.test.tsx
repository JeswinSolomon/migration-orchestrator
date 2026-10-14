import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as api from './api'
import RunDetail from './RunDetail'

vi.mock('./api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./api')>()),
  getRun: vi.fn(),
  getReconciliation: vi.fn(),
  getExceptions: vi.fn(),
  transition: vi.fn(),
  loadRun: vi.fn(),
}))

const recon = (balanced: boolean): api.Reconciliation => ({
  tenant: 'northwind',
  entities: [{ entity: 'LOAN', source: 4, loaded: balanced ? 3 : 2, quarantined: 1, balanced }],
  paymentSourceSum: 1010, paymentLoadedSum: 800, paymentQuarantinedSum: 210, balanced,
})

function setup(state: api.RunState, balanced = true) {
  vi.mocked(api.getRun).mockResolvedValue({ runId: 1, tenantId: 'northwind', state, approvedBy: null })
  vi.mocked(api.getReconciliation).mockResolvedValue(recon(balanced))
  vi.mocked(api.getExceptions).mockResolvedValue([
    { EXCEPTION_ID: 1, ENTITY: 'LOAN', LEGACY_ID: '12', REASON_CODE: 'ORPHAN_BORROWER', RESOLVED: 0 },
  ])
  render(<RunDetail runId={1} />)
}

describe('RunDetail', () => {
  beforeEach(() => vi.clearAllMocks())

  it('shows reconciliation status and quarantined rows', async () => {
    setup('INITIAL_LOAD')
    expect(await screen.findByText('ORPHAN_BORROWER')).toBeInTheDocument()
    expect(screen.getByText(/balanced/)).toHaveClass('ok')
    expect(screen.getByRole('button', { name: 'Run initial load' })).toBeInTheDocument()
  })

  it('flags an unbalanced reconciliation', async () => {
    setup('INITIAL_LOAD', false)
    expect(await screen.findByText(/not balanced/)).toHaveClass('bad')
  })

  it('lets an approver advance to SWITCHED; the server records who, so there is no name box', async () => {
    setup('FINAL_SYNC')
    vi.mocked(api.transition).mockResolvedValue({ runId: 1, tenantId: 'northwind', state: 'SWITCHED', approvedBy: 'jeswin' })
    expect(screen.queryByLabelText('Approver name')).not.toBeInTheDocument()
    await userEvent.click(await screen.findByRole('button', { name: 'Advance to SWITCHED' }))
    expect(api.transition).toHaveBeenCalledWith(1, 'SWITCHED')
  })

  it('shows the server error when a pre-check fails', async () => {
    setup('INITIAL_LOAD')
    vi.mocked(api.transition).mockRejectedValue(new api.ApiError(409, 'Pre-check failed: reconciliation is not balanced'))
    await userEvent.click(await screen.findByRole('button', { name: 'Advance to CDC_SYNC' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Pre-check failed')
  })

  it('polls while the run is active and stops when it is finished', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      setup('INITIAL_LOAD')
      await screen.findByTestId('state')
      const before = vi.mocked(api.getRun).mock.calls.length
      await vi.advanceTimersByTimeAsync(5100)
      expect(vi.mocked(api.getRun).mock.calls.length).toBeGreaterThan(before)
      expect(screen.getByTestId('live')).toHaveTextContent('Live')
    } finally {
      vi.useRealTimers()
    }
  })

  it('does not poll a finished run', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      setup('DONE')
      await screen.findByTestId('state')
      const before = vi.mocked(api.getRun).mock.calls.length
      await vi.advanceTimersByTimeAsync(15000)
      expect(vi.mocked(api.getRun).mock.calls.length).toBe(before)
      expect(screen.getByTestId('live')).toHaveTextContent('finished')
    } finally {
      vi.useRealTimers()
    }
  })

  it('offers rollback only in risky stages', async () => {
    setup('READY')
    await screen.findByTestId('state')
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Roll back' })).not.toBeInTheDocument())
  })
})
