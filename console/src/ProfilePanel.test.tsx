import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as api from './api'
import ProfilePanel from './ProfilePanel'

vi.mock('./api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./api')>()),
  getProfile: vi.fn(),
}))

describe('ProfilePanel', () => {
  beforeEach(() => vi.resetAllMocks())

  it('shows null rates and mixed date formats after the button is pressed', async () => {
    vi.mocked(api.getProfile).mockResolvedValue({
      files: [{
        file: 'borrowers.csv', rows: 3,
        columns: [{ column: 'dob', nullCount: 0, nullPct: 0, duplicateValues: 0,
          formats: { 'ISO yyyy-MM-dd': 1, 'US MM/dd/yyyy': 2 } }],
      }],
    })
    render(<ProfilePanel runId={1} />)
    await userEvent.click(screen.getByRole('button', { name: 'Profile legacy data' }))
    expect(await screen.findByText('borrowers.csv (3 rows)')).toBeInTheDocument()
    expect(screen.getByText('ISO yyyy-MM-dd: 1, US MM/dd/yyyy: 2')).toBeInTheDocument()
  })

  it('shows an error when the profile cannot be loaded', async () => {
    vi.mocked(api.getProfile).mockRejectedValue(new Error('boom'))
    render(<ProfilePanel runId={1} />)
    await userEvent.click(screen.getByRole('button', { name: 'Profile legacy data' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load the data profile.')
  })
})
