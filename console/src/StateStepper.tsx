import type { RunState } from './api'
import { STATES } from './runState'

export function StateBadge({ state }: { state: RunState }) {
  const tone = state === 'ROLLED_BACK' ? 'bad' : state === 'DONE' ? 'ok' : 'active'
  return <span className={`badge badge-${tone}`}>{state.replace('_', ' ')}</span>
}

// Shows where the run is on the cutover path; ROLLED_BACK is off the path so it gets a banner instead.
export default function StateStepper({ state }: { state: RunState }) {
  if (state === 'ROLLED_BACK') {
    return <div className="error" role="status">This run was rolled back. It is finished and cannot move again.</div>
  }
  const current = STATES.indexOf(state)
  return (
    <ol className="stepper" aria-label="Cutover progress">
      {STATES.map((s, i) => (
        <li key={s} className={i < current ? 'step done' : i === current ? 'step current' : 'step'}
          aria-current={i === current ? 'step' : undefined}>
          <span className="dot">{i < current ? '✓' : i + 1}</span>
          <span className="label">{s.replace('_', ' ')}</span>
        </li>
      ))}
    </ol>
  )
}
