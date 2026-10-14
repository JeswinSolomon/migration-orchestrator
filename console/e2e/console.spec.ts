import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

async function signIn(page: Page, username = 'e2e', password = 'e2e-pass') {
  await page.goto('/')
  await page.getByLabel('Username').fill(username)
  await page.getByLabel('Password').fill(password)
  await page.getByRole('button', { name: 'Sign in' }).click()
}

test('nobody sees runs without signing in, and a wrong password is refused', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByRole('button', { name: 'Create run' })).toHaveCount(0)
  await signIn(page, 'e2e', 'wrong-password')
  await expect(page.getByRole('alert')).toContainText('Wrong username or password')
})

test('operator creates a run, advances it and asks the agent', async ({ page }) => {
  await signIn(page)
  await expect(page.getByTestId('whoami')).toHaveText('e2e')
  await expect(page.getByTestId('orchestrator-status')).toHaveText('UP')
  await expect(page.getByTestId('ai-service-status')).toHaveText('UP')

  await page.getByLabel('Tenant id').fill('e2e-tenant')
  await page.getByRole('button', { name: 'Create run' }).click()
  await expect(page.getByTestId('state')).toHaveText('PLANNED')
  await expect(page.getByRole('list', { name: 'Cutover progress' }).locator('.current')).toContainText('PLANNED')

  await page.getByRole('button', { name: 'Advance to PROFILED' }).click()
  await expect(page.getByTestId('state')).toHaveText('PROFILED')
  await expect(page.getByRole('list', { name: 'Cutover progress' }).locator('.done')).toHaveCount(1)

  // The load has not run, so a pre-check fails and the agent should explain why.
  await page.getByRole('button', { name: 'Advance to INITIAL_LOAD' }).click()
  await expect(page.getByTestId('state')).toHaveText('INITIAL_LOAD')
  await page.getByRole('button', { name: 'Advance to CDC_SYNC' }).click()
  await expect(page.getByRole('alert').first()).toContainText('Pre-check failed')

  await page.getByRole('button', { name: 'Diagnose the last error' }).click()
  await expect(page.getByTestId('cause')).toHaveText('LOAD_NOT_RUN')
  await expect(page.getByText('Proposed actions (not executed)')).toBeVisible()
  await page.screenshot({ path: 'e2e-run-diagnosis.png', fullPage: true })
})

test('the agent answers even for a symptom it cannot explain', async ({ page }) => {
  await signIn(page)
  await page.getByLabel('Tenant id').fill('e2e-unknown')
  await page.getByRole('button', { name: 'Create run' }).click()
  await page.getByLabel('Symptom').fill('the moon is full')
  await page.getByRole('button', { name: 'Diagnose', exact: true }).click()
  await expect(page.getByTestId('diagnosis')).toBeVisible()
})
