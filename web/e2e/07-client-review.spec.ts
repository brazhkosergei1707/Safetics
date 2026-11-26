import { type Page, expect, test } from '@playwright/test'
import { t } from './support/i18n.ts'
import { state } from './support/state.ts'
import {
  clickAndConfirm,
  day,
  expectStatus,
  openBoard,
  openMyTask,
  shiftCell,
  timeRow,
  typeDate,
  ui,
} from './support/shifts.ts'
import { as, dialog, expectMessage, label, row } from './support/ui.ts'

// Розділ 7 регресійного плану: перевірка рапортів замовником у порталі.
// Рапорти з розділу 6: дні 3, 5, 6 подані, 4 — чернетка.
test.describe.configure({ mode: 'serial' })

/** «Перевірка рапортів» за минулий місяць (типово фільтр — останні 30 днів). */
async function openReview(page: Page) {
  await page.goto('/review')
  const range = page.getByTestId('review-range').locator('input')
  await typeDate(range.nth(0), day(1))
  await typeDate(range.nth(1), day(28))
}

function reviewRow(page: Page, n: number) {
  return row(page, ui(day(n)))
}

test('7.1–7.4 представник замовника: подані рапорти, без витрат; повернути лише з коментарем', async ({ browser }) => {
  const page = await as(browser, 'clientRep')
  await openReview(page)
  await expect(reviewRow(page, 3)).toContainText(t('reports.status.submitted'))
  await expect(reviewRow(page, 5)).toBeVisible()
  await expect(page.locator('.ant-table-row').filter({ hasText: ui(day(4)) })).toHaveCount(0)

  await reviewRow(page, 3).click()
  const drawer = dialog(page)
  await expect(drawer).toContainText(t('reports.interval'))
  await expect(drawer).toContainText(state().hole1)
  await expect(drawer).not.toContainText(t('reports.consumables'))
  await expect(drawer).not.toContainText(t('reports.meterReadings'))

  await drawer.getByRole('button', { name: t('review.return') }).click()
  const modal = dialog(page)
  await modal.getByRole('button', { name: t('review.return') }).click()
  await expect(modal).toContainText(t('validation.required'))
  await modal.getByLabel(label(t('review.comment'))).fill('Перевірте години DRL')
  await modal.getByRole('button', { name: t('review.return') }).click()
  await expectMessage(page, t('review.returnedMessage'))
  await page.context().close()
})

test('7.5 бурильник бачить коментар, виправляє й подає знову', async ({ browser }) => {
  const page = await as(browser, 'driller')
  const alerts = await (await page.request.get('/api/v1/alerts')).json()
  expect(alerts.items.map((a: { kind: string }) => a.kind)).toContain('report_returned')

  const report = await openMyTask(page, 'Змінний рапорт', day(3))
  await expectStatus(report, t('reports.status.returned'))
  await expect(report).toContainText('Перевірте години DRL')
  await report.getByRole('button', { name: t('reports.saveDraft') }).click()
  await expectMessage(page, t('common.saved'))
  await clickAndConfirm(report, t('reports.submit'))
  await expectStatus(report, t('reports.status.submitted'))
  await expect(report).toContainText(t('review.events.returned'))
  await page.context().close()
})

test('7.6 замовник виправляє глибину в інтервалі рядка буріння', async ({ browser }) => {
  const page = await as(browser, 'clientRep')
  await openReview(page)
  await reviewRow(page, 3).click()
  await dialog(page).getByRole('button', { name: t('review.correct') }).click()
  const modal = dialog(page)
  const first = timeRow(modal, 0)
  // У рядку буріння замовник бачить лише свердловину
  await expect(first.locator('.ant-select').nth(1)).toContainText(state().hole1)
  await first.getByPlaceholder(t('reports.depthTo')).fill('36')
  await modal.getByLabel(label(t('review.comment'))).fill('За замірами геолога — 36 м')
  await modal.getByRole('button', { name: t('review.sendCorrection') }).click()
  await expectMessage(page, t('review.correctedMessage'))
  await page.context().close()
})

test('7.7 бурильник погоджується з правкою — «Перевірено»', async ({ browser }) => {
  const page = await as(browser, 'driller')
  const report = await openMyTask(page, 'Змінний рапорт', day(3))
  await expectStatus(report, t('reports.status.corrected'))
  await expect(report).toContainText('За замірами геолога — 36 м')
  await clickAndConfirm(report, t('review.agree'))
  await expectMessage(page, t('review.agreed'))
  await page.context().close()

  // Задача рапорту закрита — у «Моїх задачах» його більше немає
  const check = await as(browser, 'driller')
  await check.goto('/my-tasks')
  await expect(check.getByText(new RegExp(`Змінний рапорт: .* · ${ui(day(3)).slice(0, 5).replace('.', '\\.')} Day$`))).toHaveCount(0)
  await check.context().close()
})

test('7.8–7.9 прийняти рапорт; менеджер замовника бачить без кнопок', async ({ browser }) => {
  const rep = await as(browser, 'clientRep')
  await openReview(rep)
  await reviewRow(rep, 5).click()
  const drawer = dialog(rep)
  await clickAndConfirm(drawer, t('review.accept'))
  await expectMessage(rep, t('review.acceptedMessage'))
  await rep.context().close()

  const manager = await as(browser, 'clientManager')
  await openReview(manager)
  await reviewRow(manager, 6).click()
  const view = dialog(manager)
  await expect(view).toContainText(t('reports.interval'))
  await expect(view.getByRole('button', { name: t('review.accept') })).toHaveCount(0)
  await expect(view.getByRole('button', { name: t('review.return') })).toHaveCount(0)
  await expect(view.getByRole('button', { name: t('review.correct') })).toHaveCount(0)
  await manager.context().close()
})

test('7.10 перевірений рапорт у чернетку повертає лише менеджер', async ({ browser }) => {
  const supervisor = await as(browser, 'supervisor')
  await openBoard(supervisor, day(8))
  await shiftCell(supervisor, day(5)).click()
  await dialog(supervisor).getByRole('button', { name: t('reports.open') }).click()
  const seen = dialog(supervisor)
  await expectStatus(seen, t('reports.status.accepted'))
  await expect(seen.getByRole('button', { name: t('reports.reopen') })).toHaveCount(0)
  await supervisor.context().close()

  const manager = await as(browser, 'manager')
  await openBoard(manager, day(8))
  await shiftCell(manager, day(5)).click()
  await dialog(manager).getByRole('button', { name: t('reports.open') }).click()
  const report = dialog(manager)
  await clickAndConfirm(report, t('reports.reopen'))
  await expectMessage(manager, t('reports.reopened'))
  await expectStatus(report, t('reports.status.draft'))
  // Знову подати — для місячного звіту лишається неперевіреним
  await clickAndConfirm(report, t('reports.submit'))
  await expectStatus(report, t('reports.status.submitted'))
  await manager.context().close()
})
