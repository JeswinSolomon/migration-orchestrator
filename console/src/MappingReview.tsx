import { useEffect, useState } from 'react'
import {
  ApiError, decideMapping, getMappings, listRuns, suggestMappings,
  type Run, type SourceColumn, type StoredMapping, type TargetColumn,
} from './api'

const SOURCES: SourceColumn[] = [
  { name: 'full_name', samples: ['ANA SMITH', 'Ben Patel'] },
  { name: 'email', samples: ['ana@example.com'] },
  { name: 'dob', samples: ['1990-05-01', '03/15/1985'] },
  { name: 'phone', samples: ['(555) 0101'] },
]
const TARGETS: TargetColumn[] = [
  { name: 'FULL_NAME', type: 'VARCHAR2' }, { name: 'EMAIL', type: 'VARCHAR2' },
  { name: 'PHONE', type: 'VARCHAR2' }, { name: 'DOB', type: 'DATE' },
]

export default function MappingReview() {
  const [runs, setRuns] = useState<Run[]>([])
  const [runId, setRunId] = useState<number | null>(null)
  const [rows, setRows] = useState<StoredMapping[]>([])
  const [warnings, setWarnings] = useState<string[]>([])
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    listRuns()
      .then((r) => { setRuns(r); if (r.length > 0) setRunId(r[r.length - 1].runId) })
      .catch(() => setError('Cannot reach the orchestrator (is it running on :8081?)'))
  }, [])

  useEffect(() => {
    if (runId === null) return
    getMappings(runId).then(setRows).catch(() => setRows([]))
  }, [runId])

  const fail = (e: unknown) => setError(e instanceof ApiError ? e.message : 'Cannot reach the orchestrator')

  async function suggest() {
    if (runId === null) return
    setError(null)
    try {
      const result = await suggestMappings(runId, SOURCES, TARGETS)
      setWarnings(result.warnings)
      setRows(await getMappings(runId))
    } catch (e) {
      fail(e)
    }
  }

  async function decide(source: string, decision: 'APPROVED' | 'REJECTED') {
    if (runId === null) return
    setError(null)
    try {
      setRows(await decideMapping(runId, source, decision))
    } catch (e) {
      fail(e)
    }
  }

  const approved = rows.filter((r) => r.DECISION === 'APPROVED').length
  const pending = rows.filter((r) => r.DECISION === 'PENDING').length

  return (
    <div>
      <h2>Mapping review</h2>
      <p>AI suggestions are never applied automatically: a signed-in reviewer approves each one, and the run cannot load while any are pending.</p>
      <label>Run{' '}
        <select value={runId ?? ''} onChange={(e) => setRunId(Number(e.target.value))}>
          {runs.map((r) => <option key={r.runId} value={r.runId}>#{r.runId} {r.tenantId} ({r.state})</option>)}
        </select>
      </label>{' '}
      <button onClick={suggest} disabled={runId === null}>Suggest mappings</button>
      {error && <div role="alert" className="error">{error}</div>}
      {warnings.map((w) => <div key={w} className="error">{w}</div>)}
      {rows.length > 0 && (
        <>
          <table>
            <thead><tr><th>Source</th><th>Target</th><th>Confidence</th><th>Method</th><th>Reason</th><th>Decision</th></tr></thead>
            <tbody>
              {rows.map((s) => (
                <tr key={s.SOURCE_COL} className={s.REQUIRES_REVIEW ? 'warn' : ''}>
                  <td>{s.SOURCE_COL}</td><td>{s.TARGET_COL}</td><td>{Number(s.CONFIDENCE).toFixed(2)}</td>
                  <td>{s.METHOD}{s.REQUIRES_REVIEW ? ' · needs review' : ''}</td><td>{s.REASON}</td>
                  <td>
                    <span data-testid={`decision-${s.SOURCE_COL}`}>{s.DECISION}</span>{' '}
                    <button onClick={() => decide(s.SOURCE_COL, 'APPROVED')}>Approve {s.SOURCE_COL}</button>
                    <button onClick={() => decide(s.SOURCE_COL, 'REJECTED')}>Reject {s.SOURCE_COL}</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <p data-testid="summary">{approved} of {rows.length} approved, {pending} pending</p>
        </>
      )}
    </div>
  )
}
