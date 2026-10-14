import { describe, expect, it } from 'vitest'
import { canRollback, nextState } from './runState'

describe('runState', () => {
  it('moves one step forward and stops at DONE', () => {
    expect(nextState('PLANNED')).toBe('PROFILED')
    expect(nextState('FINAL_SYNC')).toBe('SWITCHED')
    expect(nextState('DONE')).toBeUndefined()
    expect(nextState('ROLLED_BACK')).toBeUndefined()
  })

  it('allows rollback only from risky stages', () => {
    expect(canRollback('READY')).toBe(false)
    expect(canRollback('FREEZE')).toBe(true)
    expect(canRollback('DONE')).toBe(false)
  })
})
