import { useState } from 'react'
import type { FormEvent } from 'react'
import { signIn } from './api'
import type { Session } from './api'

export default function Login({ onSignedIn, notice }: { onSignedIn: (s: Session) => void; notice?: string }) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')

  function submit(e: FormEvent) {
    e.preventDefault()
    if (username.trim() === '' || password === '') return
    onSignedIn(signIn(username.trim(), password))
  }

  return (
    <form onSubmit={submit} aria-label="Sign in" className="card">
      <h2>Sign in</h2>
      {notice && <div role="alert" className="error">{notice}</div>}
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
