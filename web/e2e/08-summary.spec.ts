import { expect, test } from '@playwright/test'
import { t } from './support/i18n.ts'
import { state } from './support/state.ts'
import { day } from './support/shifts.ts'
import { as, row } from './support/ui.ts'

// Розділ 8 регресійного плану: зведені звіти (підрядник і портал замовника).
test.describe.configure({ mode: 'serial' })

/** Місяць рапортів сценарію в колонці «Період»: 10.2026. */
const period = () => {
  const d = day(1)
  return `${String(d.getMonth() + 1).padStart(2, '0')}.${d.getFullYear()}`
}

test('8.1 менеджер: бурова × місяць, CSV', async ({ browser }) => {
  const page = await as(browser, 'manager')
  await page.goto('/reports')
  const line = row(page, period()).filter({ hasText: state().rig })
  await expect(line).toBeVisible()
  const download = page.waitForEvent('download')
  await page.getByRole('button', { name: t('summary.exportCsv') }).click()
  expect((await download).suggestedFilename()).toMatch(/\.csv$/)
  await page.context().close()
})

test('8.2 портал: той самий місяць, без внутрішніх втрат підрядника', async ({ browser }) => {
  const manager = await as(browser, 'manager')
  const own = await (await manager.request.get(`/api/v1/reports/summary?from=${iso1()}&to=${iso28()}&period=month&depthStep=100`)).json()
  await manager.context().close()

  const page = await as(browser, 'clientRep')
  await page.goto('/reports')
  await expect(row(page, period()).filter({ hasText: state().rig })).toBeVisible()
  const portal = await (await page.request.get(`/api/v1/reports/summary?from=${iso1()}&to=${iso28()}&period=month&depthStep=100`)).json()
  // Метри й оплачувані години — як у підрядника; неоплачуваних годин і витрат замовник не бачить
  expect(Number(own.total.meters)).toBeGreaterThan(0)
  expect(own.total.nonBillableMinutes).toBeGreaterThan(0)
  expect(portal.total.meters).toBe(own.total.meters)
  expect(portal.total.billableMinutes).toBe(own.total.billableMinutes)
  expect(portal.total.nonBillableMinutes).toBe(0)
  expect(portal.total.consumables ?? []).toHaveLength(0)
  await page.context().close()
})

const pad = (n: number) => String(n).padStart(2, '0')
const iso1 = () => {
  const d = day(1)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-01`
}
const iso28 = () => {
  const d = day(28)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-28`
}
