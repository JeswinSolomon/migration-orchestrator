import { useEffect, useState } from 'react'
import { currentSession, signOut } from './api'
import type { Session } from './api'
import Login from './Login'
import MappingReview from './MappingReview'
import RunsPage from './RunsPage'

type Health = { status: string } | 'unreachable'

async function fetchHealth(url: string): Promise<Health> {
  try {
    return await (await fetch(url)).json()
  } catch {
    return 'unreachable'
  }
}

function HealthCard({ name, health }: { name: string; health: Health | null }) {
  const label = health === null ? 'checking...' : health === 'unreachable' ? 'unreachable' : health.status
  return (
    <div className="card">
      <strong>{name}</strong>: <span data-testid={`${name}-status`}>{label}</span>
    </div>
  )
}

export default function App() {
  const [session, setSession] = useState<Session | null>(currentSession())
  const [notice, setNotice] = useState<string | undefined>()
  const [tab, setTab] = useState<'runs' | 'mapping'>('runs')
  const [orchestrator, setOrchestrator] = useState<Health | null>(null)
  const [ai, setAi] = useState<Health | null>(null)

  useEffect(() => {
    fetchHealth('/orchestrator/actuator/health').then(setOrchestrator)
    fetchHealth('/ai/health').then(setAi)
  }, [])

  // api.ts signs out on any 401 (wrong password, or credentials changed on the server)
  useEffect(() => {
    const onSignedOut = () => {
      setSession(null)
      setNotice('Please sign in again')
    }
    window.addEventListener('migration:signed-out', onSignedOut)
    return () => window.removeEventListener('migration:signed-out', onSignedOut)
  }, [])

  return (
    <main style={{ padding: 24 }}>
      <h1>Migration Orchestrator Console</h1>
      <div style={{ display: 'flex' }}>
        <HealthCard name="orchestrator" health={orchestrator} />
        <HealthCard name="ai-service" health={ai} />
      </div>
      {session === null ? (
        <Login notice={notice} onSignedIn={(s) => { setNotice(undefined); setSession(s) }} />
      ) : (
        <>
          <p>Signed in as <strong data-testid="whoami">{session.username}</strong>{' '}
            <button onClick={signOut}>Sign out</button></p>
          <nav>
            <button className={tab === 'runs' ? 'active' : ''} onClick={() => setTab('runs')}>Runs</button>
            <button className={tab === 'mapping' ? 'active' : ''} onClick={() => setTab('mapping')}>Mapping review</button>
          </nav>
          {tab === 'runs' ? <RunsPage /> : <MappingReview />}
        </>
      )}
    </main>
  )
}
