import { expect, test } from '@playwright/test'
import { t } from './support/i18n.ts'
import { PASSWORD, PEOPLE, type Person, phone, save, state } from './support/state.ts'
import { as, choose, dialog, firstOfLastMonth, options, pickDate, row, label, expectClosed } from './support/ui.ts'

// Розділ 3 регресійного плану: замовник, контактні особи з порталом, проєкт з відповідальним менеджером.
test.describe.configure({ mode: 'serial' })

test('3.1–3.2 головний менеджер додає замовників і контактних осіб з входом у портал', async ({ browser }) => {
  const s = state()
  const page = await as(browser, 'head')
  await expect(page.getByTestId('header-role')).toContainText(t('roles.head_manager'))
  await page.goto('/customers')

  for (const name of [s.customerName, s.otherCustomerName]) {
    await page.getByRole('button', { name: t('customers.add') }).click()
    const form = dialog(page)
    await form.getByLabel(label(t('customers.name'))).fill(name)
    await form.getByRole('button', { name: t('common.save') }).click()
    // Після створення картка лишається відкритою — з розділом контактних осіб
    await expect(dialog(page).getByRole('button', { name: t('customers.addContact') })).toBeVisible()
    if (name === s.customerName) {
      await addContact(page, 'clientRep', 'roles.client_rep')
      await addContact(page, 'clientManager', 'roles.client_manager')
    }
    await page.keyboard.press('Escape')
    await expect(row(page, name)).toBeVisible()
  }
  await page.context().close()
})

async function addContact(page: import('@playwright/test').Page, person: Person, role: string) {
  await dialog(page).getByRole('button', { name: t('customers.addContact') }).click()
  const contact = dialog(page)
  await contact.getByLabel(label(t('customers.fullName'))).fill(PEOPLE[person].name)
  await contact.getByLabel(label(t('customers.phone'))).fill(phone(person))
  await contact.getByRole('button', { name: t('common.save') }).click()
  await expectClosed(contact)
  const item = row(dialog(page), PEOPLE[person].name)
  await item.getByRole('button', { name: t('customers.portalAccess') }).click()
  const portal = dialog(page)
  await choose(page, portal.getByLabel(label(t('session.role'))), t(role))
  await portal.getByLabel(label(t('customers.initialPassword'))).fill(PASSWORD)
  await portal.getByRole('button', { name: t('customers.grantPortal') }).click()
  await expect(row(dialog(page), PEOPLE[person].name)).toContainText(t(role))
}

test('3.3–3.4 проєкт: відповідальним — лише роль «Менеджер»', async ({ browser }) => {
  const s = state()
  const page = await as(browser, 'head')
  for (const [code, customer, withManager] of [
    [s.projectCode, s.customerName, true],
    [`${s.projectCode}-B`, s.otherCustomerName, false],
  ] as const) {
    // Після створення відкривається сторінка проєкту — повертаємось до списку
    await page.goto('/projects')
    await page.getByRole('button', { name: t('projects.add') }).click()
    const form = dialog(page)
    await form.getByLabel(label(t('projects.code'))).fill(code)
    await form.getByLabel(label(t('projects.name'))).fill(`Проєкт ${code}`)
    await choose(page, form.getByLabel(label(t('projects.customer'))), customer)
    if (withManager) {
      const managers = await options(page, form.getByLabel(label(t('projects.manager'))))
      expect(managers.some((m) => m.includes(PEOPLE.manager.name))).toBeTruthy()
      expect(managers.some((m) => m.includes(PEOPLE.head.name) || m.includes(PEOPLE.admin.name))).toBeFalsy()
      await choose(page, form.getByLabel(label(t('projects.manager'))), PEOPLE.manager.name)
    }
    await pickDate(form.getByLabel(label(t('projects.startDate'))), firstOfLastMonth())
    await form.getByRole('button', { name: '2×12', exact: true }).click()
    await form.getByRole('button', { name: t('common.save') }).click()
    await expectClosed(form)
    await expect(page.getByRole('heading', { level: 2 })).toContainText(code)
  }
  await page.goto('/projects')
  await expect(row(page, s.projectCode)).toBeVisible()
  await expect(row(page, `${s.projectCode}-B`)).toBeVisible()
  save({ otherProjectCode: `${s.projectCode}-B` })
  await page.context().close()
})

test('3.5 менеджер бачить лише свій проєкт і його замовника, без створення', async ({ browser }) => {
  const s = state()
  const page = await as(browser, 'manager')
  await page.goto('/projects')
  await expect(row(page, s.projectCode)).toBeVisible()
  await expect(page.locator('.ant-table-row').filter({ hasText: `${s.projectCode}-B` })).toHaveCount(0)
  await expect(page.getByRole('button', { name: t('projects.add') })).toHaveCount(0)
  await page.goto('/customers')
  await expect(row(page, s.customerName)).toBeVisible()
  await expect(page.locator('.ant-table-row').filter({ hasText: s.otherCustomerName })).toHaveCount(0)
  await expect(page.getByRole('button', { name: t('customers.add') })).toHaveCount(0)
  await page.context().close()
})
