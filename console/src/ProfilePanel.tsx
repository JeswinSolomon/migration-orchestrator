import { useState } from 'react'
import { getProfile, type Profile } from './api'

export default function ProfilePanel({ runId }: { runId: number }) {
  const [profile, setProfile] = useState<Profile | null>(null)
  const [error, setError] = useState<string | null>(null)

  async function load() {
    setError(null)
    try {
      setProfile(await getProfile(runId))
    } catch {
      setProfile(null)
      setError('Could not load the data profile.')
    }
  }

  return (
    <section aria-label="Data profile">
      <h3>Data profile</h3>
      <button onClick={load}>Profile legacy data</button>
      {error && <div role="alert" className="error">{error}</div>}
      {profile?.files.map((f) => (
        <div key={f.file}>
          <h4>{f.file} ({f.rows} rows)</h4>
          <table>
            <thead><tr><th>Column</th><th>Nulls</th><th>Duplicates</th><th>Date formats</th></tr></thead>
            <tbody>
              {f.columns.map((c) => (
                <tr key={c.column}>
                  <td>{c.column}</td>
                  <td>{c.nullCount} ({c.nullPct}%)</td>
                  <td>{c.duplicateValues}</td>
                  <td>{Object.entries(c.formats).map(([k, v]) => `${k}: ${v}`).join(', ') || '-'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ))}
    </section>
  )
}
