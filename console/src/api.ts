export type RunState =
  | 'PLANNED' | 'PROFILED' | 'INITIAL_LOAD' | 'CDC_SYNC' | 'SHADOW_VERIFY' | 'READY'
  | 'FREEZE' | 'FINAL_SYNC' | 'SWITCHED' | 'MONITOR' | 'DONE' | 'ROLLED_BACK'

export interface Run {
  runId: number
  tenantId: string
  state: RunState
  approvedBy: string | null
}

export interface EntityRecon {
  entity: string
  source: number
  loaded: number
  quarantined: number
  balanced: boolean
}

export interface Reconciliation {
  tenant: string
  entities: EntityRecon[]
  paymentSourceSum: number
  paymentLoadedSum: number
  paymentQuarantinedSum: number
  balanced: boolean
}

export interface MigrationException {
  EXCEPTION_ID: number
  ENTITY: string
  LEGACY_ID: string
  REASON_CODE: string
  RESOLVED: number
}

export interface SourceColumn { name: string; samples: string[] }
export interface TargetColumn { name: string; type: string }

export interface Suggestion {
  source: string
  target: string
  confidence: number
  method: string
  reason: string
  requires_review: boolean
}

export interface MappingResult {
  suggestions: Suggestion[]
  unmapped_sources: string[]
  unmapped_targets: string[]
  llm_used: boolean
  warnings: string[]
}

export class ApiError extends Error {
  status: number
  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

const CREDENTIALS_KEY = 'migration.credentials'

export interface Session { username: string; header: string }

// Kept in sessionStorage (cleared when the tab closes), never localStorage.
export function currentSession(): Session | null {
  const raw = sessionStorage.getItem(CREDENTIALS_KEY)
  return raw ? (JSON.parse(raw) as Session) : null
}

export function signIn(username: string, password: string): Session {
  const session = { username, header: 'Basic ' + btoa(`${username}:${password}`) }
  sessionStorage.setItem(CREDENTIALS_KEY, JSON.stringify(session))
  return session
}

export function signOut() {
  sessionStorage.removeItem(CREDENTIALS_KEY)
  window.dispatchEvent(new Event('migration:signed-out'))
}

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  const session = currentSession()
  // Credentials belong to the orchestrator only: the ai-service reads runs with its own service account.
  if (session && url.startsWith('/orchestrator')) headers.Authorization = session.header
  const res = await fetch(url, { ...init, headers })
  if (res.status === 401) {
    signOut()
    throw new ApiError(401, 'Please sign in again')
  }
  if (res.status === 403) {
    throw new ApiError(403, 'Your account is not allowed to do that')
  }
  if (!res.ok) {
    let message = res.statusText
    try {
      const body = await res.json()
      message = body.error ?? message
    } catch {
      // keep statusText
    }
    throw new ApiError(res.status, message)
  }
  return res.json() as Promise<T>
}

const RUNS = '/orchestrator/api/runs'
const post = (body: unknown): RequestInit => ({ method: 'POST', body: JSON.stringify(body) })

export const listRuns = () => request<Run[]>(RUNS)
export const getRun = (id: number) => request<Run>(`${RUNS}/${id}`)
export const createRun = (tenantId: string) => request<Run>(RUNS, post({ tenantId }))
export const transition = (id: number, target: string) =>
  request<Run>(`${RUNS}/${id}/transitions`, post({ target }))
export const loadRun = (id: number) => request<unknown>(`${RUNS}/${id}/load`, { method: 'POST' })
export const getReconciliation = (id: number) => request<Reconciliation>(`${RUNS}/${id}/reconciliation`)
export const getExceptions = (id: number) => request<MigrationException[]>(`${RUNS}/${id}/exceptions`)

export interface StoredMapping {
  SOURCE_COL: string
  TARGET_COL: string
  CONFIDENCE: number
  METHOD: string
  REASON: string
  REQUIRES_REVIEW: number
  DECISION: 'PENDING' | 'APPROVED' | 'REJECTED'
  DECIDED_BY: string | null
}

export interface AgentAction {
  description: string
  owner: string
  mutating: boolean
  requires_approval: boolean
}

export interface Diagnosis {
  cause: string
  confidence: number
  summary: string
  evidence: string[]
  actions: AgentAction[]
  narrative: string | null
  llm_used: boolean
  warnings: string[]
}

// The agent lives in the ai-service; it reads the run itself, so only the id and an optional symptom are sent.
export const diagnoseRun = (runId: number, symptom?: string) =>
  request<Diagnosis>('/ai/agent/diagnose', post({ run_id: runId, symptom: symptom || undefined }))

export const suggestMappings = (runId: number, sources: SourceColumn[], targets: TargetColumn[]) =>
  request<MappingResult>(`${RUNS}/${runId}/mappings/suggest`, post({ sources, targets }))
export const getMappings = (runId: number) => request<StoredMapping[]>(`${RUNS}/${runId}/mappings`)
export const decideMapping = (runId: number, source: string, decision: 'APPROVED' | 'REJECTED') =>
  request<StoredMapping[]>(`${RUNS}/${runId}/mappings/${encodeURIComponent(source)}`,
    { method: 'PUT', body: JSON.stringify({ decision }) })

