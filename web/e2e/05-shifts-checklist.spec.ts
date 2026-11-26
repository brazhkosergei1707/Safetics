import { expect, test } from '@playwright/test'
import { t } from './support/i18n.ts'
import { PEOPLE, state } from './support/state.ts'
import { clickAndConfirm, day, expectStatus, newShiftCell, openBoard, openMyTask, shiftCell, typeDate } from './support/shifts.ts'
import { as, choose, dialog, expectMessage, label } from './support/ui.ts'

// Розділ 5 регресійного плану: зміни (вручну й за період) і чек-лист зміни.
// Дні минулого місяця: 3 — зміна вручну, 4–8 — за період; чек-листи: 3 — некритичне «Ні», 8 — критичне.
test.describe.configure({ mode: 'serial' })

test('5.1–5.4 зміна вручну: відповідальний обов’язковий, лише з входом; задачі зміни', async ({ browser }) => {
  const page = await as(browser, 'manager')
  await openBoard(page, day(8))
  await newShiftCell(page, day(3)).click()
  const modal = dialog(page)
  await expect(modal).toContainText(t('shifts.newShift'))

  // Бригада без відповідального — помилка біля поля
  const lead = modal.getByLabel(label(t('shifts.lead')))
  await choose(page, modal.getByLabel(label(t('shifts.crewMembers'))), PEOPLE.helper.name)
  await page.keyboard.press('Escape')
  await modal.getByRole('button', { name: t('common.save') }).click()
  await expect(modal).toContainText(t('validation.required'))

  // Помбур без входу — недоступний як відповідальний
  await lead.click()
  const dropdown = page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)').last()
  await expect(dropdown.locator('.ant-select-item-option-disabled')).toContainText(PEOPLE.helper.name)
  await dropdown.locator('.ant-select-item-option').filter({ hasText: PEOPLE.driller.name }).click()
  await modal.getByRole('button', { name: t('common.save') }).click()

  // Після створення відкривається картка зміни: задачі чек-листа й рапорту
  const shift = dialog(page)
  await expect(shift).toContainText('Чек-лист зміни')
  await expect(shift).toContainText('Змінний рапорт')
  await expect(shift).toContainText(PEOPLE.helper.name)
  await page.context().close()
})

test('5.5 зміни за період: бригада й відповідальний — з попередньої зміни', async ({ browser }) => {
  const page = await as(browser, 'manager')
  await openBoard(page, day(8))
  await page.getByRole('button', { name: t('shifts.generate') }).click()
  const modal = dialog(page)
  const inputs = modal.locator('.ant-picker-range input')
  await typeDate(inputs.nth(0), day(4))
  await typeDate(inputs.nth(1), day(8))
  await choose(page, modal.getByLabel(label(t('shifts.equipment'))), state().rig)
  await modal.getByRole('button', { name: t('shifts.generateOk') }).click()
  // Денна й нічна зміни × 5 днів
  await expectMessage(page, t('shifts.generated', { count: 10 }))
  await expect(shiftCell(page, day(6))).toContainText(PEOPLE.driller.name)
  await page.context().close()
})

test('5.6–5.7 чек-лист: галочки «Так» / «Ні», некритичне «Ні» → «Сервіс»', async ({ browser }) => {
  const page = await as(browser, 'driller')
  const checklist = await openMyTask(page, 'Чек-лист зміни', day(3))
  await expect(checklist).toContainText(t('checklists.progress', { answered: 0, total: 10 }))
  await expect(checklist.getByRole('button', { name: t('checklists.submit') })).toBeDisabled()

  const items = checklist.locator('.ant-list-item')
  const count = await items.count()
  for (let i = 0; i < count; i++) {
    const item = items.nth(i)
    const text = await item.innerText()
    const fail = text.includes('Немає витоків гідравліки')
    await item.getByRole('checkbox', { name: fail ? t('checklists.results.fail') : t('checklists.results.pass') }).check()
    if (fail) await item.getByPlaceholder(t('checklists.comment')).fill('Підтікає штуцер насоса')
  }
  await expect(checklist).toContainText(t('checklists.progress', { answered: 10, total: 10 }))
  await clickAndConfirm(checklist, t('checklists.submit'))
  await expectMessage(page, t('checklists.failedMessage'))
  await expectStatus(checklist, t('checklists.status.failed'))
  await expect(checklist).toContainText(t('checklists.serviceCreated'))
  await page.context().close()
})

test('5.8 критичний пункт «Ні» → алерт менеджеру', async ({ browser }) => {
  const driller = await as(browser, 'driller')
  const checklist = await openMyTask(driller, 'Чек-лист зміни', day(8))
  await expect(checklist).toContainText(t('checklists.progress', { answered: 0, total: 10 }))
  const items = checklist.locator('.ant-list-item')
  const count = await items.count()
  for (let i = 0; i < count; i++) {
    const item = items.nth(i)
    const fail = (await item.innerText()).includes('Вогнегасники')
    await item.getByRole('checkbox', { name: fail ? t('checklists.results.fail') : t('checklists.results.pass') }).check()
    if (fail) await item.getByPlaceholder(t('checklists.comment')).fill('Вогнегасник розряджений')
  }
  await expect(checklist).toContainText(t('checklists.progress', { answered: 10, total: 10 }))
  await clickAndConfirm(checklist, t('checklists.submit'))
  await expectMessage(driller, t('checklists.failedMessage'))
  await driller.context().close()

  const manager = await as(browser, 'manager')
  const alerts = await (await manager.request.get('/api/v1/alerts')).json()
  expect(alerts.items.map((a: { kind: string }) => a.kind)).toContain('critical_checklist')
  await manager.context().close()
})
