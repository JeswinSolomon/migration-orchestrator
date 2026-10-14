import { useState } from 'react'
import type { FormEvent } from 'react'
import { signIn } from './api'
import type { Session } from './api'

export default function Login({ onSignedIn, notice }: { onSignedIn: (s: Session) => void; notice?: string }) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')

  const [error, setError] = useState<string | undefined>()

  // Verify the credentials before opening the session, so a wrong password never shows the signed-in view.
  async function submit(e: FormEvent) {
    e.preventDefault()
    if (username.trim() === '' || password === '') return
    setError(undefined)
    const session = signIn(username.trim(), password)
    try {
      const res = await fetch('/orchestrator/api/runs', { headers: { Authorization: session.header } })
      if (res.status === 401) {
        sessionStorage.removeItem('migration.credentials')
        setError('Wrong username or password')
        return
      }
    } catch {
      sessionStorage.removeItem('migration.credentials')
      setError('Cannot reach the orchestrator')
      return
    }
    onSignedIn(session)
  }

  return (
    <form onSubmit={submit} aria-label="Sign in" className="card">
      <h2>Sign in</h2>
      {(error ?? notice) && <div role="alert" className="error">{error ?? notice}</div>}
      <label>Username{' '}
        <input value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" />
      </label>{' '}
      <label>Password{' '}
        <input type="password" value={password} onChange={(e) => setPassword(e.target.value)}
          autoComplete="current-password" />
      </label>{' '}
      <button type="submit">Sign in</button>
    </form>
  )
}
