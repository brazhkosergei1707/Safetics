import { expect, test } from '@playwright/test'
import { t } from './support/i18n.ts'
import { PASSWORD, PEOPLE, phone, save, state } from './support/state.ts'
import { as, choose, confirmPopover, dialog, expectMessage, options, row, label, button } from './support/ui.ts'

// Розділ 2 регресійного плану: налаштування компанії адміном.
test.describe.configure({ mode: 'serial' })

test('2.1–2.2 адмін додає головного менеджера й менеджера; ролі «Виконавець» немає', async ({ browser }) => {
  const page = await as(browser, 'admin')
  await page.goto('/users')
  for (const [person, role] of [
    ['head', 'roles.head_manager'],
    ['manager', 'roles.manager'],
  ] as const) {
    await page.getByRole('button', { name: t('users.add') }).click()
    const form = dialog(page)
    await form.getByLabel(label(t('customers.fullName'))).fill(PEOPLE[person].name)
    await form.getByLabel(label(t('customers.phone'))).fill(phone(person))
    const roles = await options(page, form.getByLabel(label(t('session.role'))))
    expect(roles).not.toContain(t('roles.worker'))
    expect(roles).toContain(t('roles.head_manager'))
    await choose(page, form.getByLabel(label(t('session.role'))), t(role))
    await form.getByLabel(label(t('customers.initialPassword'))).fill(PASSWORD)
    await form.getByRole('button', { name: t('common.save') }).click()
    await expect(row(page, PEOPLE[person].name)).toContainText(t(role))
  }
  await page.context().close()
})

test('2.3 адмін не керує виконавцями: у «Виконавцях» лише «Посади»', async ({ browser }) => {
  const page = await as(browser, 'admin')
  await page.goto('/personnel')
  await expect(page.getByRole('tab', { name: t('personnel.tabs.positions') })).toBeVisible()
  await expect(page.getByRole('tab', { name: t('personnel.tabs.people') })).toHaveCount(0)
  await page.context().close()
})

test('2.4–2.7 типи: порядок вкладок, бібліотека, видалення', async ({ browser }) => {
  // Інші компанії — джерело бібліотеки. База може бути чистою (CI), тож створюємо обидві самі:
  // «будівництво» дає «Генератор», «розвідувальне буріння» — ту саму «Бурову установку», що вже є в нас
  const platform = await as(browser, 'platform')
  for (const [no, templateKey] of [
    ['098', 'exploration-drilling'],
    ['099', 'construction'],
  ] as const) {
    const other = await platform.request.post('/api/v1/platform/companies', {
      data: {
        name: `E2E Library ${state().run}-${no}`,
        templateKey,
        adminFullName: `Library Admin ${no}`,
        adminPhone: `+38063${state().run}${no}`,
        adminPassword: PASSWORD,
      },
    })
    expect(other.ok()).toBeTruthy()
  }
  await platform.context().close()

  const page = await as(browser, 'admin')
  await page.goto('/assets')
  await page.getByRole('button', { name: t('assetTypes.title') }).click()
  const types = dialog(page)

  // Перетягнути «Табір» на перше місце → перша вкладка
  await row(types, 'Табір').dragTo(row(types, 'Бурова установка'))
  await expect(page.getByRole('tab').first()).toContainText('Табір')

  // Бібліотека: додати «Генератор» з іншої компанії, потім видалити
  await types.getByRole('button', { name: t('assetTypes.fromLibrary') }).click()
  const library = dialog(page)
  await expect(row(library, 'Бурова установка')).toContainText(t('assetTypes.libraryExists'))
  await row(library, 'Генератор').getByRole('button', { name: t('assetTypes.libraryAdd') }).click()
  await expectMessage(page, t('assetTypes.libraryAdded', { name: 'Генератор' }))
  await library.getByRole('button', { name: 'Close' }).or(library.locator('.ant-modal-close')).first().click()
  await expect(page.getByRole('tab', { name: /Генератор/ })).toBeVisible()
  await row(dialog(page), 'Генератор').getByRole('button', { name: t('common.delete') }).click()
  await confirmPopover(page)
  await expectMessage(page, t('assetTypes.deleted'))
  await expect(page.getByRole('tab', { name: /Генератор/ })).toHaveCount(0)
  await page.context().close()
})

test('2.9–2.10 обладнання: бурова й авто; тип з обладнанням не видаляється', async ({ browser }) => {
  const s = state()
  const rig = `RIG-${s.run}`
  const truck = `TRK-${s.run}`
  const page = await as(browser, 'admin')
  await page.goto('/assets')

  await page.getByRole('tab', { name: /Бурова установка/ }).click()
  await page.getByRole('button', { name: button(t('assets.add')) }).click()
  let form = dialog(page)
  await form.getByLabel(label(t('assets.code'))).fill(rig)
  await form.getByLabel(label('Модель')).fill('LF-90')
  await form.getByRole('button', { name: t('common.save') }).click()
  await expect(row(page, rig)).toBeVisible()

  await page.getByRole('tab', { name: /Автомобіль/ }).click()
  await page.getByRole('button', { name: button(t('assets.add')) }).click()
  form = dialog(page)
  await form.getByLabel(label(t('assets.code'))).fill(truck)
  await form.getByLabel(label('Держномер')).fill(`AA${s.run}BB`)
  await form.getByRole('button', { name: t('common.save') }).click()
  await expect(row(page, truck)).toBeVisible()

  await page.getByRole('button', { name: t('assetTypes.title') }).click()
  await row(dialog(page), 'Бурова установка').getByRole('button', { name: t('common.delete') }).click()
  await confirmPopover(page)
  await expectMessage(page, t('assetTypes.hasAssets', { count: 1 }))
  save({ rig, truck })
  await page.context().close()
})
