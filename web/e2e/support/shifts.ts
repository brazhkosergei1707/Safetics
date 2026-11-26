import { type Locator, type Page, expect } from '@playwright/test'
import { t } from './i18n.ts'
import { state } from './state.ts'
import { choose, dialog, openProject, tab } from './ui.ts'

/** День минулого місяця: зміни й рапорти сценарію — у місяці, який потім закриває місячний звіт. */
export function day(n: number): Date {
  const now = new Date()
  return new Date(now.getFullYear(), now.getMonth() - 1, n)
}

const pad = (n: number) => String(n).padStart(2, '0')
/** 2026-10-03 — дата в API й data-testid дошки змін. */
export const iso = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
/** 03.10.2026 — дата в інтерфейсі. */
export const ui = (d: Date) => `${pad(d.getDate())}.${pad(d.getMonth() + 1)}.${d.getFullYear()}`
/** «03.10 Day» — зміна в назвах задач («Чек-лист зміни: RIG-… · 03.10 Day»). */
export const label = (d: Date) => `${pad(d.getDate())}.${pad(d.getMonth() + 1)} Day`

/** Поле дати Ant Design (DatePicker): ввести DD.MM.YYYY і підтвердити. */
export async function typeDate(input: Locator, d: Date) {
  await input.click()
  await input.fill(ui(d))
  await input.press('Enter')
}

/** Вкладка «Зміни» проєкту сценарію, тиждень, що закінчується датою end. */
export async function openBoard(page: Page, end: Date) {
  await openProject(page, state().projectCode)
  await tab(page, t('projects.tabs.workShifts'))
  await typeDate(page.getByTestId('shift-board-end').locator('input'), end)
}

/** Клітинка дошки: наявна денна зміна бурової на дату. */
export function shiftCell(page: Page, d: Date): Locator {
  return page.getByTestId(`shift-${state().rig}-${iso(d)}-day`)
}

/** «+ Day» — додати зміну вручну. */
export function newShiftCell(page: Page, d: Date): Locator {
  return page.getByTestId(`shift-new-${state().rig}-${iso(d)}-day`)
}

/** Задача з «Моїх задач» за типом і зміною: «Змінний рапорт: RIG-… · 03.10 Day». */
export async function openMyTask(page: Page, type: 'Чек-лист зміни' | 'Змінний рапорт', d: Date) {
  await page.goto('/my-tasks')
  // Назва задачі — посилання без href (Typography.Link), тож шукаємо за текстом
  await page.getByText(new RegExp(`^${type}: .* · ${label(d).replace('.', '\\.')}$`)).click()
  const task = dialog(page)
  await task.getByRole('button', { name: type === 'Чек-лист зміни' ? t('checklists.open') : t('reports.open') }).click()
  return dialog(page)
}

/** Рядок журналу часу (0 — перший). */
export function timeRow(scope: Locator, index: number): Locator {
  return scope.getByTestId(`time-row-${index}`)
}

/** Заповнити рядок журналу: час, код (за кодом операції: 'DRL', 'MAINT'), інтервал (лише для буріння). */
export async function fillTimeRow(
  page: Page,
  row: Locator,
  values: { from?: string; to?: string; code?: string; depthFrom?: number; depthTo?: number; core?: number },
) {
  if (values.from) await typeTime(row.getByPlaceholder(t('reports.from')), values.from)
  if (values.to) await typeTime(row.getByPlaceholder(t('reports.to')), values.to)
  if (values.code) await choose(page, row.getByRole('combobox').first(), new RegExp(`^${values.code} ·`), values.code)
  if (values.depthFrom != null) await row.getByPlaceholder(t('reports.depthFrom')).fill(String(values.depthFrom))
  if (values.depthTo != null) await row.getByPlaceholder(t('reports.depthTo')).fill(String(values.depthTo))
  if (values.core != null) await row.getByPlaceholder(t('reports.coreLength')).fill(String(values.core))
}

async function typeTime(input: Locator, value: string) {
  await input.click()
  await input.fill(value)
  await input.press('Enter')
}

/** Обрана задача рядка (друге поле вибору). */
export function rowTask(row: Locator): Locator {
  return row.locator('.ant-select').nth(1)
}

/** Підтвердити Popconfirm після натискання кнопки. */
export async function clickAndConfirm(scope: Locator, name: string) {
  await scope.getByRole('button', { name }).click()
  const page = scope.page()
  await page.locator('.ant-popover:not(.ant-popover-hidden) .ant-popconfirm-buttons .ant-btn-primary').last().click()
}

/** Тег статусу в заголовку вікна рапорту / чек-листа. */
export async function expectStatus(scope: Locator, text: string) {
  await expect(scope.locator('.ant-drawer-title .ant-tag, .ant-drawer-header .ant-tag').first()).toHaveText(text)
}
