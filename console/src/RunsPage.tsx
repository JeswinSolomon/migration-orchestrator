import { useCallback, useEffect, useState } from 'react'
import { ApiError, createRun, listRuns, type Run } from './api'
import RunDetail from './RunDetail'
import { StateBadge } from './StateStepper'

export default function RunsPage() {
  const [runs, setRuns] = useState<Run[]>([])
  const [selected, setSelected] = useState<number | null>(null)
  const [tenant, setTenant] = useState('')
  const [error, setError] = useState<string | null>(null)

  const reload = useCallback(async () => {
    try {
      setRuns(await listRuns())
    } catch {
      setError('Cannot reach the orchestrator (is it running on :8081?)')
    }
  }, [])

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void reload()
  }, [reload])

  async function create() {
    setError(null)
    try {
      const run = await createRun(tenant)
      setTenant('')
      setSelected(run.runId)
      await reload()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Unexpected error')
    }
  }

  return (
    <div>
      <h2>Migration runs</h2>
      {error && <div role="alert" className="error">{error}</div>}
      <div>
        <input aria-label="Tenant id" placeholder="Tenant id, e.g. northwind" value={tenant}
          onChange={(e) => setTenant(e.target.value)} />
        <button onClick={create}>Create run</button>
      </div>
      <table>
        <thead><tr><th>Run</th><th>Tenant</th><th>State</th><th /></tr></thead>
        <tbody>
          {runs.map((r) => (
            <tr key={r.runId}>
              <td>{r.runId}</td><td>{r.tenantId}</td><td><StateBadge state={r.state} /></td>
              <td><button onClick={() => setSelected(r.runId)}>Open run {r.runId}</button></td>
            </tr>
          ))}
        </tbody>
      </table>
      {selected !== null && <RunDetail key={selected} runId={selected} onChanged={reload} />}
    </div>
  )
}
