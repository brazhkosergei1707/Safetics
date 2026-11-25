import { expect, test } from '@playwright/test'
import { t } from './support/i18n.ts'
import { PASSWORD, PEOPLE, phone, state } from './support/state.ts'
import { as, choose, dialog, label } from './support/ui.ts'

// Розділ 1 регресійного плану: компанія створюється суперадміном, перший адмін входить.
test.describe.configure({ mode: 'serial' })

test('1.1–1.2 суперадмін створює компанію з шаблоном і першим адміном', async ({ browser }) => {
  const s = state()
  const page = await as(browser, 'platform')
  await expect(page).toHaveURL(/\/platform/)
  await page.getByRole('button', { name: t('platform.add') }).click()
  const form = dialog(page)
  await form.getByLabel(label(t('customers.name'))).fill(s.companyName)
  await choose(page, form.getByLabel(label(t('platform.template'))), 'Розвідувальне буріння')
  await form.getByLabel(label(t('customers.fullName'))).fill(PEOPLE.admin.name)
  await form.getByLabel(label(t('customers.phone'))).fill(phone('admin'))
  await form.getByLabel(label(t('customers.initialPassword'))).fill(PASSWORD)
  await form.getByRole('button', { name: t('common.save') }).click()
  await expect(page.getByRole('row').filter({ hasText: s.companyName })).toContainText(t('platform.suspend'))
  await page.context().close()
})

test('1.3–1.4 адмін входить: компанія, роль, обладнання з шаблону', async ({ browser }) => {
  const s = state()
  const page = await as(browser, 'admin')
  await expect(page.getByTestId('header-company')).toHaveText(s.companyName)
  await expect(page.getByTestId('header-role')).toContainText(t('roles.admin'))
  await page.goto('/assets')
  const rigTab = page.getByRole('tab', { name: /Бурова установка/ })
  await expect(rigTab).toBeVisible()
  await expect(rigTab).toContainText(t('assets.primary'))
  await page.context().close()
})

test('вхід з неправильним паролем не пускає', async ({ browser }) => {
  const page = await as(browser, 'platform')
  await page.context().clearCookies()
  await page.goto('/login')
  await page.locator('#phone').fill(phone('admin'))
  await page.locator('#password').fill('wrong-password')
  await page.getByRole('button', { name: t('login.submit') }).click()
  await expect(page).toHaveURL(/\/login/)
  await expect(page.getByRole('alert')).toBeVisible()
  await page.context().close()
})
