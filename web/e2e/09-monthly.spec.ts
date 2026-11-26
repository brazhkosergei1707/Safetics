import { type Locator, type Page, expect, test } from '@playwright/test'
import { t } from './support/i18n.ts'
import { state } from './support/state.ts'
import { clickAndConfirm, day, iso, openBoard, openMyTask, shiftCell, ui } from './support/shifts.ts'
import { as, button, choose, dialog, expectMessage, label, openProject, row, submitAndClose, tab } from './support/ui.ts'

// Розділ 9 регресійного плану: місячний звіт (версії, включення, коригування, надсилання,
// повернення й затвердження замовником) і закриття місяця.
// Стан після розділу 7: рапорт дня 3 перевірено, 5 і 6 подані (неперевірені), 4 — чернетка.
test.describe.configure({ mode: 'serial' })

async function openMonthly(page: Page) {
  await openProject(page, state().projectCode)
  await tab(page, t('projects.tabs.monthly'))
  return page.getByRole('tabpanel').filter({ visible: true }).first()
}

/** Значення плитки «Разом, м» у живому перегляді місяця. */
async function totalMeters(panel: Locator): Promise<number> {
  const value = panel
    .locator('.ant-statistic')
    .filter({ hasText: t('monthly.totalMeters') })
    .first()
    .locator('.ant-statistic-content')
  return Number((await value.innerText()).replace(/[^\d.,-]/g, '').replace(',', '.'))
}

function versionRow(panel: Locator, status: string): Locator {
  return panel.locator('.ant-table-row').filter({ hasText: status }).filter({ has: panel.page().getByRole('button') }).first()
}

async function generate(page: Page, panel: Locator) {
  await clickAndConfirm(panel, t('monthly.generate'))
  await expectMessage(page, t('monthly.generated'))
}

async function send(page: Page, panel: Locator) {
  const draft = panel.locator('.ant-table-row').filter({ has: page.getByRole('button', { name: t('monthly.send') }) })
  await draft.getByRole('button', { name: t('monthly.send') }).click()
  await page.locator('.ant-popover:not(.ant-popover-hidden) .ant-popconfirm-buttons .ant-btn-primary').last().click()
  await expectMessage(page, t('monthly.sentMessage'))
}

async function alertKinds(page: Page): Promise<string[]> {
  const alerts = await (await page.request.get('/api/v1/alerts')).json()
  return alerts.items.map((a: { kind: string }) => a.kind)
}

test('9.1–9.3 живий перегляд: перевірені, неперевірені, включення й коригування', async ({ browser }) => {
  const page = await as(browser, 'manager')
  const panel = await openMonthly(page)
  await expect(panel.locator('.ant-statistic').filter({ hasText: t('monthly.acceptedReports') })).toContainText('1')
  await expect(panel.locator('.ant-statistic').filter({ hasText: t('monthly.draftReports') })).toContainText('1')
  await expect(row(panel, ui(day(5)))).toBeVisible()
  await expect(row(panel, ui(day(6)))).toBeVisible()

  // 9.2 Включити неперевірений рапорт дня 5 — разом зростає
  const before = await totalMeters(panel)
  await row(panel, ui(day(5))).getByRole('switch').click()
  await expect.poll(() => totalMeters(panel)).toBeGreaterThan(before)

  // 9.3 Коригування +2 м
  const included = await totalMeters(panel)
  await panel.getByRole('button', { name: button(t('monthly.addAdjustment')) }).click()
  const modal = dialog(page)
  await choose(page, modal.getByLabel(label(t('monthly.kind'))), t('monthly.kinds.meters'))
  await modal.getByLabel(label(t('monthly.amount'))).fill('2')
  await modal.getByLabel(label(t('review.comment'))).fill('Перебур за актом геолога')
  await submitAndClose(modal, t('common.save'))
  await expect(row(panel, 'Перебур за актом геолога')).toBeVisible()
  await expect.poll(() => totalMeters(panel)).toBeCloseTo(included + 2, 1)
  await page.context().close()
})

test('9.4–9.6 дві генерації (v1 замінено), CSV і друк, надіслати', async ({ browser }) => {
  const page = await as(browser, 'manager')
  const panel = await openMonthly(page)
  await generate(page, panel)
  await page.keyboard.press('Escape')
  await generate(page, panel)
  const report = dialog(page)
  await expect(report).toContainText('v2')

  const download = page.waitForEvent('download')
  await report.getByRole('button', { name: button('CSV') }).click()
  expect((await download).suggestedFilename()).toMatch(new RegExp(`^${state().projectCode}_.*_v2\\.csv$`))
  const popup = page.waitForEvent('popup')
  await report.getByRole('button', { name: button(t('monthly.print')) }).click()
  const printed = await popup
  await expect(printed.locator('body')).toContainText(t('monthly.totalMeters'))
  await printed.close()
  await page.keyboard.press('Escape')

  await expect(versionRow(panel, t('monthly.status.superseded'))).toContainText('1')
  await send(page, panel)
  await expect(panel.getByRole('button', { name: t('monthly.generate') })).toBeDisabled()
  await page.context().close()
})

test('9.7–9.9 портал: менеджер замовника бачить надісланий звіт, геолог — без кнопок; повернути', async ({ browser }) => {
  const client = await as(browser, 'clientManager')
  expect(await alertKinds(client)).toContain('monthly_sent')
  await client.goto('/monthly')
  await expect(client.locator('.ant-table-row')).toHaveCount(1)
  await expect(row(client, state().projectCode)).toContainText(t('monthly.status.sent'))
  await client.context().close()

  const rep = await as(browser, 'clientRep')
  await rep.goto('/monthly')
  await row(rep, state().projectCode).click()
  const view = dialog(rep)
  await expect(view).toContainText(t('monthly.totalMeters'))
  await expect(view.getByRole('button', { name: t('monthly.approve') })).toHaveCount(0)
  await rep.context().close()

  const manager = await as(browser, 'clientManager')
  await manager.goto('/monthly')
  await row(manager, state().projectCode).click()
  await dialog(manager).getByRole('button', { name: t('review.return') }).click()
  const modal = dialog(manager)
  await modal.getByLabel(label(t('review.comment'))).fill('Додайте акт перебуру')
  await submitAndClose(modal, t('review.return'))
  await expectMessage(manager, t('monthly.returnedMessage'))
  await manager.context().close()
})

test('9.10–9.11 нова версія після повернення; затвердження закриває місяць', async ({ browser }) => {
  const page = await as(browser, 'manager')
  expect(await alertKinds(page)).toContain('monthly_returned')
  const panel = await openMonthly(page)
  await expect(panel.page().getByText('Додайте акт перебуру').first()).toBeVisible()
  await generate(page, panel)
  await page.keyboard.press('Escape')
  await send(page, panel)
  await page.context().close()

  const client = await as(browser, 'clientManager')
  await client.goto('/monthly')
  await client.locator('.ant-table-row').filter({ hasText: t('monthly.status.sent') }).first().click()
  const view = dialog(client)
  await expect(view).toContainText('v3')
  await clickAndConfirm(view, t('monthly.approve'))
  await expectMessage(client, t('monthly.approvedMessage'))
  await client.context().close()

  const check = await as(browser, 'manager')
  const closed = await openMonthly(check)
  await expect(closed).toContainText(t('monthly.closed'))
  await check.context().close()
})

test('9.12–9.13 закритий місяць: рапорт лише для читання, бригаду не змінити', async ({ browser }) => {
  const driller = await as(browser, 'driller')
  const report = await openMyTask(driller, 'Змінний рапорт', day(6))
  await expect(report).toContainText(t('reports.status.submitted'))
  await expect(report.getByRole('button', { name: t('reports.saveDraft') })).toHaveCount(0)
  await driller.context().close()

  const manager = await as(browser, 'manager')
  await openBoard(manager, day(8))
  await shiftCell(manager, day(6)).click()
  await dialog(manager).getByRole('button', { name: t('common.save') }).click()
  await expectMessage(manager, t('apiErrors.month_closed'))
  await manager.context().close()
})

test('9.14 поточний місяць: коригувальний запис з посиланням на зміну закритого місяця', async ({ browser }) => {
  const page = await as(browser, 'manager')
  const panel = await openMonthly(page)
  const now = new Date()
  const month = page.getByTestId('monthly-month').locator('input')
  await month.click()
  await month.fill(`${String(now.getMonth() + 1).padStart(2, '0')}.${now.getFullYear()}`)
  await month.press('Enter')
  await expect(panel).not.toContainText(t('monthly.closed'))

  await panel.getByRole('button', { name: button(t('monthly.addAdjustment')) }).click()
  const modal = dialog(page)
  await choose(page, modal.getByLabel(label(t('monthly.kind'))), t('monthly.kinds.meters'))
  await modal.getByLabel(label(t('monthly.amount'))).fill('-1')
  await choose(page, modal.getByLabel(label(t('monthly.correctsShift'))), iso(day(6)))
  await modal.getByLabel(label(t('review.comment'))).fill('Уточнення глибини за минулий місяць')
  await submitAndClose(modal, t('common.save'))
  await expect(row(panel, 'Уточнення глибини за минулий місяць')).toBeVisible()
  await page.context().close()
})
