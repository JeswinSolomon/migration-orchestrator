import { resolve } from 'node:path'
import { defineConfig } from '@playwright/test'

// Run from the console folder, so the repo root is one level up.
const root = resolve(process.cwd(), '..').replace(/\\/g, '/')

// Locally we reuse the Edge already on the machine (the Playwright browser download can be blocked);
// in CI we install Playwright's own Chromium.
export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  workers: 1,
  reporter: 'list',
  use: {
    baseURL: 'http://localhost:5173',
    channel: process.env.CI ? undefined : 'msedge',
    screenshot: 'only-on-failure',
  },
  webServer: [
    {
      command: `java -jar ${root}/orchestrator-service/target/orchestrator-service-0.1.0-SNAPSHOT.jar`,
      url: 'http://localhost:8081/actuator/health',
      reuseExistingServer: false,
      timeout: 120_000,
      env: {
        TARGET_DB_URL: 'jdbc:h2:mem:e2e;MODE=Oracle;DB_CLOSE_DELAY=-1',
        TARGET_DB_USER: 'sa',
        TARGET_DB_PASSWORD: '',
        MIGRATION_LEGACY_DIR: `${root}/legacy-data/data`,
        MIGRATION_USERS: 'e2e:e2e-pass:OPERATOR|REVIEWER|APPROVER|READER;agent:agent-pass:READER',
      },
    },
    {
      command: 'python -m uvicorn app.main:app --port 8000',
      cwd: `${root}/ai-service`,
      url: 'http://localhost:8000/health',
      reuseExistingServer: false,
      env: { ORCHESTRATOR_USER: 'agent', ORCHESTRATOR_PASSWORD: 'agent-pass' },
    },
    { command: 'npm run dev -- --port 5173 --strictPort', url: 'http://localhost:5173', reuseExistingServer: false },
  ],
})
