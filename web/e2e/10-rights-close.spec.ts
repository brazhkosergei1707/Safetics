import { expect, test } from '@playwright/test'
import { t } from './support/i18n.ts'
import { PEOPLE, state } from './support/state.ts'
import { ui } from './support/shifts.ts'
import { as, choose, dialog, label, openProject, row, submitAndClose, tab } from './support/ui.ts'

// Розділи 10–11 регресійного плану: вибіркові права й закриття проєкту.
// 10.4 (представник іншого замовника) і 10.5 (адмін без «Люди», покрито 2.3) — вручну / в інших тестах.
test.describe.configure({ mode: 'serial' })

test('10.1–10.3 менеджер не бачить чужого проєкту; бурильник — лише свої задачі, без замовників', async ({ browser }) => {
  const s = state()
  const manager = await as(browser, 'manager')
  await manager.goto('/projects')
  await expect(row(manager, s.projectCode)).toBeVisible()
  await expect(manager.locator('.ant-table-row').filter({ hasText: s.otherProjectCode })).toHaveCount(0)
  await manager.context().close()

  const driller = await as(browser, 'driller')
  await driller.goto('/my-tasks')
  await expect(driller.getByText(/^Змінний рапорт: /).first()).toBeVisible()
  // Головні задачі свердловин — не на бурильнику
  await expect(driller.getByText(new RegExp(s.hole1))).toHaveCount(0)
  await expect(driller.getByRole('menuitem', { name: t('nav.customers') })).toHaveCount(0)
  await driller.context().close()
})

test('11.1–11.2 закриття проєкту звільняє людей і обладнання', async ({ browser }) => {
  const s = state()
  const page = await as(browser, 'head')
  await openProject(page, s.projectCode)
  await page.getByRole('button', { name: t('projects.close') }).click()
  await page.locator('.ant-popover:not(.ant-popover-hidden) .ant-popconfirm-buttons .ant-btn-primary').last().click()
  await expect(page.getByText(t('projects.status.closed')).first()).toBeVisible()

  // Призначення закінчились напередодні
  const yesterday = new Date()
  yesterday.setDate(yesterday.getDate() - 1)
  await tab(page, t('projects.tabs.people'))
  // Типово — лише поточні й заплановані; закінчені — у «Усі»
  await page.getByRole('tabpanel').filter({ visible: true }).getByText(t('assignments.history'), { exact: true }).click()
  await expect(row(page, PEOPLE.driller.name)).toContainText(ui(yesterday))

  // Бурову можна одразу призначити на інший проєкт
  await openProject(page, s.otherProjectCode)
  await tab(page, t('projects.tabs.equipment'))
  await page.getByRole('button', { name: t('assignments.assignEquipment') }).click()
  const form = dialog(page)
  await choose(page, form.getByLabel(label(t('assignments.equipment'))), s.rig)
  await submitAndClose(form, t('common.save'))
  await expect(row(page, s.rig)).toBeVisible()
  await page.context().close()
})
