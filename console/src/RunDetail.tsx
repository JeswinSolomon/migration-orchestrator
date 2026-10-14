import { useCallback, useEffect, useState } from 'react'
import {
  ApiError, getExceptions, getReconciliation, getRun, loadRun, transition,
  type MigrationException, type Reconciliation, type Run,
} from './api'
import DiagnosePanel from './DiagnosePanel'
import StateStepper from './StateStepper'
import { canRollback, nextState } from './runState'

const POLL_MS = 5000

export default function RunDetail({ runId, onChanged }: { runId: number; onChanged?: () => void }) {
  const [run, setRun] = useState<Run | null>(null)
  const [recon, setRecon] = useState<Reconciliation | null>(null)
  const [exceptions, setExceptions] = useState<MigrationException[]>([])
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const refresh = useCallback(async () => {
    try {
      const [r, rc, ex] = await Promise.all([getRun(runId), getReconciliation(runId), getExceptions(runId)])
      setRun(r)
      setRecon(rc)
      setExceptions(ex)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Cannot reach the orchestrator')
    }
  }, [runId])

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void refresh()
  }, [refresh])

  // Poll while the run can still change; stop on terminal states so we do not hammer the server.
  const live = run !== null && run.state !== 'DONE' && run.state !== 'ROLLED_BACK'
  useEffect(() => {
    if (!live) return
    const timer = setInterval(() => void refresh(), POLL_MS)
    return () => clearInterval(timer)
  }, [live, refresh])

  async function act(action: () => Promise<unknown>) {
    setBusy(true)
    setError(null)
    try {
      await action()
      await refresh()
      onChanged?.()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Unexpected error')
    } finally {
      setBusy(false)
    }
  }

  if (!run) {
    return error ? <div role="alert" className="error">{error}</div> : <p>Loading run...</p>
  }

  const next = nextState(run.state)

  return (
    <section>
      <h2>Run {run.runId} · {run.tenantId} · <span data-testid="state">{run.state}</span></h2>
      <StateStepper state={run.state} />
      <p className="hint" data-testid="live">{live ? `Live: refreshing every ${POLL_MS / 1000}s` : 'Run finished: not refreshing'}</p>
      {run.approvedBy && <p>Approved by {run.approvedBy}</p>}
      {error && <div role="alert" className="error">{error}</div>}

      <div>
        {run.state === 'INITIAL_LOAD' && (
          <button disabled={busy} onClick={() => act(() => loadRun(runId))}>Run initial load</button>
        )}
        {next && (
          <button disabled={busy} onClick={() => act(() => transition(runId, next))}>
            Advance to {next}
          </button>
        )}
        {canRollback(run.state) && (
          <button disabled={busy} onClick={() => act(() => transition(runId, 'ROLLED_BACK'))}>Roll back</button>
        )}
      </div>

      <DiagnosePanel runId={runId} lastError={error} />

      <h3>Reconciliation {recon && (recon.balanced
        ? <span className="ok">✓ balanced</span> : <span className="bad">✗ not balanced</span>)}</h3>
      {recon && (
        <>
          <table>
            <thead><tr><th>Entity</th><th>Source</th><th>Loaded</th><th>Quarantined</th><th>OK</th></tr></thead>
            <tbody>
              {recon.entities.map((e) => (
                <tr key={e.entity}>
                  <td>{e.entity}</td><td>{e.source}</td><td>{e.loaded}</td><td>{e.quarantined}</td>
                  <td className={e.balanced ? 'ok' : 'bad'}>{e.balanced ? '✓' : '✗'}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p>Payments: source {recon.paymentSourceSum} = loaded {recon.paymentLoadedSum} + quarantined {recon.paymentQuarantinedSum}</p>
        </>
      )}

      <h3>Quarantined rows ({exceptions.length})</h3>
      {exceptions.length === 0 ? <p>None.</p> : (
        <table>
          <thead><tr><th>Entity</th><th>Legacy id</th><th>Reason</th></tr></thead>
          <tbody>
            {exceptions.map((x) => (
              <tr key={x.EXCEPTION_ID}><td>{x.ENTITY}</td><td>{x.LEGACY_ID}</td><td>{x.REASON_CODE}</td></tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  )
}
