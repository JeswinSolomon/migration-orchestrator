import { useState } from 'react'
import { ApiError, diagnoseRun, type Diagnosis } from './api'

const MAX_SYMPTOM = 500

export default function DiagnosePanel({ runId, lastError }: { runId: number; lastError?: string | null }) {
  const [symptom, setSymptom] = useState('')
  const [result, setResult] = useState<Diagnosis | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function run(text: string) {
    setBusy(true)
    setError(null)
    try {
      setResult(await diagnoseRun(runId, text.trim()))
    } catch (e) {
      setResult(null)
      if (e instanceof ApiError) {
        setError(e.status === 404 ? 'The agent cannot find this run.' : `The agent could not read the run (${e.status}).`)
      } else {
        setError('Cannot reach the AI service (is it running on :8000?)')
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <section aria-label="Diagnose">
      <h3>Ask the diagnosis agent</h3>
      <p className="hint">Read-only. The agent only reads this run and proposes actions; a person must do them.</p>
      <div>
        <input aria-label="Symptom" placeholder="What went wrong? (optional)" maxLength={MAX_SYMPTOM}
          value={symptom} onChange={(e) => setSymptom(e.target.value)} />
        <button disabled={busy} onClick={() => run(symptom)}>{busy ? 'Diagnosing...' : 'Diagnose'}</button>
        {lastError && (
          <button disabled={busy} onClick={() => { setSymptom(lastError); void run(lastError) }}>
            Diagnose the last error
          </button>
        )}
      </div>
      {error && <div role="alert" className="error">{error}</div>}

      {result && (
        <div className="card" data-testid="diagnosis">
          <p>
            <strong data-testid="cause">{result.cause}</strong>{' '}
            <span className={result.cause === 'UNKNOWN' ? 'bad' : 'ok'}>
              confidence {Math.round(result.confidence * 100)}%
            </span>
          </p>
          <p>{result.summary}</p>
          {result.cause === 'UNKNOWN' && <p className="warn">The agent is not sure. Check the evidence yourself.</p>}
          {result.narrative && <p><em>{result.narrative}</em></p>}
          <h4>Evidence</h4>
          <ul>{result.evidence.map((e) => <li key={e}>{e}</li>)}</ul>
          <h4>Proposed actions (not executed)</h4>
          <ul>
            {result.actions.map((a) => (
              <li key={a.description}>
                <strong>{a.owner}</strong>: {a.description}
                {a.mutating && <span className="bad"> · changes data or state, needs approval</span>}
              </li>
            ))}
          </ul>
          {result.warnings.map((w) => <p key={w} className="warn">{w}</p>)}
        </div>
      )}
    </section>
  )
}
