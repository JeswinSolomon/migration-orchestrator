import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { currentSession, listRuns, signOut } from './api'
import App from './App'

afterEach(() => {
  sessionStorage.clear()
  vi.unstubAllGlobals()
})

function stubFetch(handler: (url: string, init?: RequestInit) => Response) {
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => handler(url, init))
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

describe('authentication', () => {
  it('shows only the login form until signed in, then sends a Basic header', async () => {
    const fetchMock = stubFetch((url) => (url.includes('/api/runs') ? json([]) : json({ status: 'UP' })))
    render(<App />)

    expect(screen.getByRole('form', { name: 'Sign in' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Runs' })).not.toBeInTheDocument()

    await userEvent.type(screen.getByLabelText('Username'), 'ana')
    await userEvent.type(screen.getByLabelText('Password'), 'pw')
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByTestId('whoami')).toHaveTextContent('ana')
    const runsCall = fetchMock.mock.calls.find(([u]) => u.includes('/api/runs'))
    const headers = runsCall?.[1]?.headers as Record<string, string> | undefined
    expect(headers?.Authorization).toBe('Basic ' + btoa('ana:pw'))
  })

  it('drops the session and asks again when the server answers 401', async () => {
    stubFetch(() => json({}, 401))
    sessionStorage.setItem('migration.credentials', JSON.stringify({ username: 'ana', header: 'Basic x' }))
    render(<App />)
    expect(await screen.findByRole('form', { name: 'Sign in' })).toBeInTheDocument()
    expect(await screen.findByRole('alert')).toHaveTextContent('sign in again')
    expect(currentSession()).toBeNull()
  })

  it('reports a 403 as a permission problem without signing out', async () => {
    stubFetch(() => json({}, 403))
    sessionStorage.setItem('migration.credentials', JSON.stringify({ username: 'rita', header: 'Basic x' }))
    await expect(listRuns()).rejects.toMatchObject({ status: 403, message: 'Your account is not allowed to do that' })
    expect(currentSession()).not.toBeNull()
  })

  it('signing out clears the stored credentials', () => {
    sessionStorage.setItem('migration.credentials', JSON.stringify({ username: 'ana', header: 'Basic x' }))
    signOut()
    expect(currentSession()).toBeNull()
  })
})
