import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as api from './api'
import MappingReview from './MappingReview'

vi.mock('./api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./api')>()),
  listRuns: vi.fn(),
  getMappings: vi.fn(),
  suggestMappings: vi.fn(),
  decideMapping: vi.fn(),
}))

const row = (source: string, target: string, decision: api.StoredMapping['DECISION'], review = 0): api.StoredMapping => ({
  SOURCE_COL: source, TARGET_COL: target, CONFIDENCE: 0.9, METHOD: review ? 'llm' : 'exact', REASON: 'r',
  REQUIRES_REVIEW: review, DECISION: decision, DECIDED_BY: decision === 'PENDING' ? null : 'ana',
})

describe('MappingReview', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(api.listRuns).mockResolvedValue([{ runId: 7, tenantId: 'northwind', state: 'PROFILED', approvedBy: null }])
    vi.mocked(api.getMappings).mockResolvedValue([])
  })

  it('stores suggestions on the run and persists each human decision', async () => {
    const pendingRows = [row('full_name', 'FULL_NAME', 'PENDING'), row('cust_ph', 'PHONE', 'PENDING', 1)]
    vi.mocked(api.suggestMappings).mockResolvedValue({
      suggestions: [], unmapped_sources: [], unmapped_targets: [], llm_used: true, warnings: [],
    })
    vi.mocked(api.getMappings).mockResolvedValueOnce([]).mockResolvedValue(pendingRows)
    vi.mocked(api.decideMapping).mockResolvedValue([row('full_name', 'FULL_NAME', 'APPROVED'), pendingRows[1]])

    render(<MappingReview />)
    await screen.findByText(/#7 northwind/)
    await userEvent.click(screen.getByRole('button', { name: 'Suggest mappings' }))

    expect(await screen.findByTestId('summary')).toHaveTextContent('0 of 2 approved, 2 pending')
    expect(screen.getByText(/llm · needs review/)).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Approve full_name' }))
    expect(api.decideMapping).toHaveBeenCalledWith(7, 'full_name', 'APPROVED')
    expect(screen.getByTestId('summary')).toHaveTextContent('1 of 2 approved, 1 pending')
  })

  it('shows the orchestrator error when approval is refused', async () => {
    vi.mocked(api.getMappings).mockResolvedValue([row('full_name', 'FULL_NAME', 'PENDING')])
    vi.mocked(api.decideMapping).mockRejectedValue(new api.ApiError(403, 'Your account is not allowed to do that'))

    render(<MappingReview />)
    await userEvent.click(await screen.findByRole('button', { name: 'Approve full_name' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('not allowed')
  })

  it('explains when the orchestrator is down', async () => {
    vi.mocked(api.listRuns).mockRejectedValue(new Error('network'))
    render(<MappingReview />)
    expect(await screen.findByRole('alert')).toHaveTextContent('Cannot reach the orchestrator')
  })
})
