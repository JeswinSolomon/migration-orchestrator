import type { RunState } from './api'

export const STATES: RunState[] = [
  'PLANNED', 'PROFILED', 'INITIAL_LOAD', 'CDC_SYNC', 'SHADOW_VERIFY', 'READY',
  'FREEZE', 'FINAL_SYNC', 'SWITCHED', 'MONITOR', 'DONE',
]

const ROLLBACK_FROM: RunState[] = ['FREEZE', 'FINAL_SYNC', 'SWITCHED', 'MONITOR']

// Mirrors RunState.java; the server stays the source of truth and re-checks every move.
export function nextState(state: RunState): RunState | undefined {
  const i = STATES.indexOf(state)
  return i >= 0 && i < STATES.length - 1 ? STATES[i + 1] : undefined
}

export const canRollback = (state: RunState) => ROLLBACK_FROM.includes(state)
