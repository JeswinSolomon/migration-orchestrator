import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as api from './api'
import DiagnosePanel from './DiagnosePanel'

vi.mock('./api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./api')>()),
  diagnoseRun: vi.fn(),
}))

const diagnosis = (over: Partial<api.Diagnosis> = {}): api.Diagnosis => ({
  cause: 'MAPPING_PENDING', confidence: 0.8, summary: 'Loading is blocked until mappings are decided.',
  evidence: ['get_mappings: 1 of 1 mappings PENDING'],
  actions: [{ description: 'Approve or reject the pending mapping', owner: 'reviewer', mutating: true, requires_approval: true }],
  narrative: null, llm_used: false, warnings: [], ...over,
})

describe('DiagnosePanel', () => {
  beforeEach(() => vi.clearAllMocks())

  it('shows cause, evidence and proposals, and sends the typed symptom', async () => {
    vi.mocked(api.diagnoseRun).mockResolvedValue(diagnosis())
    render(<DiagnosePanel runId={4} />)
    await userEvent.type(screen.getByLabelText('Symptom'), 'load blocked')
    await userEvent.click(screen.getByRole('button', { name: 'Diagnose' }))
    expect(await screen.findByTestId('cause')).toHaveTextContent('MAPPING_PENDING')
    expect(api.diagnoseRun).toHaveBeenCalledWith(4, 'load blocked')
    expect(screen.getByText(/1 of 1 mappings PENDING/)).toBeInTheDocument()
    expect(screen.getByText(/not executed/)).toBeInTheDocument()
    expect(screen.getByText(/needs approval/)).toBeInTheDocument()
  })

  it('warns when the agent abstains', async () => {
    vi.mocked(api.diagnoseRun).mockResolvedValue(diagnosis({ cause: 'UNKNOWN', confidence: 0.2, actions: [] }))
    render(<DiagnosePanel runId={4} />)
    await userEvent.click(screen.getByRole('button', { name: 'Diagnose' }))
    expect(await screen.findByText(/not sure/)).toBeInTheDocument()
  })

  it('can diagnose the last error shown on the run', async () => {
    vi.mocked(api.diagnoseRun).mockResolvedValue(diagnosis())
    render(<DiagnosePanel runId={4} lastError="Pre-check failed: 1 mapping(s) still need review" />)
    await userEvent.click(screen.getByRole('button', { name: 'Diagnose the last error' }))
    expect(api.diagnoseRun).toHaveBeenCalledWith(4, 'Pre-check failed: 1 mapping(s) still need review')
  })

  it('explains when the AI service is unreachable or the run is missing', async () => {
    vi.mocked(api.diagnoseRun).mockRejectedValueOnce(new TypeError('Failed to fetch'))
    render(<DiagnosePanel runId={4} />)
    await userEvent.click(screen.getByRole('button', { name: 'Diagnose' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Cannot reach the AI service')

    vi.mocked(api.diagnoseRun).mockRejectedValueOnce(new api.ApiError(404, 'x'))
    await userEvent.click(screen.getByRole('button', { name: 'Diagnose' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('cannot find this run')
  })
})
