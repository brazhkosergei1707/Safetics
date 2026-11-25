import { expect, test } from '@playwright/test'
import { t } from './support/i18n.ts'
import { PASSWORD, PEOPLE, type Person, phone, save, state } from './support/state.ts'
import { as, choose, dialog, firstOfLastMonth, openProject, pickDate, row, tab, label, expectClosed } from './support/ui.ts'

// Розділ 4 регресійного плану: виконавці, призначення людей і обладнання, послуги.
test.describe.configure({ mode: 'serial' })

const CREW: { person: Person; position: string; login: boolean }[] = [
  { person: 'supervisor', position: 'Супервайзер', login: true },
  { person: 'driller', position: 'Бурильник', login: true },
  { person: 'helper', position: 'Помічник бурильника', login: false },
]

test('4.1–4.2 менеджер додає виконавців і дає вхід (лише роль «Виконавець»)', async ({ browser }) => {
  const page = await as(browser, 'manager')
  await page.goto('/personnel')
  for (const { person, position, login } of CREW) {
    await page.getByRole('button', { name: t('personnel.add') }).click()
    const form = dialog(page)
    await form.getByLabel(label(t('customers.fullName'))).fill(PEOPLE[person].name)
    await choose(page, form.getByLabel(label(t('personnel.position'))), position)
    if (login) await form.getByLabel(label(t('customers.phone'))).fill(phone(person))
    await form.getByRole('button', { name: t('common.save') }).click()
    await expectClosed(form)
    await expect(row(page, PEOPLE[person].name)).toContainText(position)
  }
  for (const { person } of CREW.filter((c) => c.login)) {
    await row(page, PEOPLE[person].name).getByText(PEOPLE[person].name).click()
    await dialog(page).getByRole('button', { name: t('personnel.grantLogin') }).click()
    const modal = dialog(page)
    await expect(modal).toContainText(t('roles.worker'))
    await expect(modal.getByLabel(label(t('session.role')))).toHaveCount(0)
    await modal.getByLabel(label(t('customers.initialPassword'))).fill(PASSWORD)
    await modal.getByRole('button', { name: t('customers.grantPortal') }).click()
    await expectClosed(modal)
    await page.keyboard.press('Escape')
  }
  await page.context().close()
})

test('4.3–4.4 призначення людей і обладнання на проєкт; задача «Бурова на проєкті»', async ({ browser }) => {
  const s = state()
  const page = await as(browser, 'manager')
  await openProject(page, s.projectCode)

  await tab(page, t('projects.tabs.people'))
  for (const { person } of CREW) {
    await page.getByRole('button', { name: t('assignments.assignPerson') }).click()
    const form = dialog(page)
    await choose(page, form.getByLabel(label(t('assignments.person'))), PEOPLE[person].name)
    await pickDate(form.getByLabel(label(t('assignments.from'))), firstOfLastMonth())
    await form.getByRole('button', { name: t('common.save') }).click()
    await expectClosed(form)
    await expect(row(page, PEOPLE[person].name)).toBeVisible()
  }

  await tab(page, t('projects.tabs.equipment'))
  for (const code of [s.rig, s.truck]) {
    await page.getByRole('button', { name: t('assignments.assignEquipment') }).click()
    const form = dialog(page)
    await choose(page, form.getByLabel(label(t('assignments.equipment'))), code)
    await pickDate(form.getByLabel(label(t('assignments.from'))), firstOfLastMonth())
    await form.getByRole('button', { name: t('common.save') }).click()
    await expectClosed(form)
  }
  await expect(row(page, s.rig)).toContainText(t('assets.primary'))

  await tab(page, t('projects.tabs.tasks'))
  await expect(page.getByText(/Бурова на проєкті/).first()).toBeVisible()
  await page.context().close()
})

test('4.5 свердловини з головними задачами', async ({ browser }) => {
  const s = state()
  const page = await as(browser, 'manager')
  await openProject(page, s.projectCode)
  await tab(page, t('projects.tabs.services'))
  const holes = [`BH-${s.run}-1`, `BH-${s.run}-2`]
  for (const code of holes) {
    await page.getByRole('button', { name: /Нова послуга/ }).click()
    const form = dialog(page)
    await form.getByLabel(label(t('services.code'))).fill(code)
    await form.getByLabel(label('Проєктна глибина')).fill('300')
    await form.getByRole('button', { name: t('common.save') }).click()
    await expectClosed(form)
    await expect(row(page, code)).toContainText(t('services.status.planned'))
  }
  save({ hole1: holes[0], hole2: holes[1] })
  await page.context().close()
})

test('4.6 бурильник: без замовників і користувачів', async ({ browser }) => {
  const page = await as(browser, 'driller')
  await expect(page.getByTestId('header-role')).toContainText(t('roles.worker'))
  await expect(page.getByRole('menuitem', { name: t('nav.customers') })).toHaveCount(0)
  await expect(page.getByRole('menuitem', { name: t('nav.users') })).toHaveCount(0)
  await page.context().close()
})
