import { type Browser, type Locator, type Page, expect } from '@playwright/test'
import { t } from './i18n.ts'
import { PASSWORD, PLATFORM_ADMIN, type Person, phone } from './state.ts'

/** Нова вкладка з мовою інтерфейсу «Українська» (тексти беремо з uk.ts). */
export async function newPage(browser: Browser): Promise<Page> {
  const context = await browser.newContext()
  await context.addInitScript(() => localStorage.setItem('safetics.lang', 'uk'))
  return context.newPage()
}

export async function login(page: Page, userPhone: string, password: string) {
  await page.goto('/login')
  await page.locator('#phone').fill(userPhone)
  await page.locator('#password').fill(password)
  await page.getByRole('button', { name: t('login.submit') }).click()
  await expect(page).not.toHaveURL(/\/login/)
}

/** Увійти учасником сценарію в окремій вкладці (власні cookie). */
export async function as(browser: Browser, person: Person | 'platform'): Promise<Page> {
  const page = await newPage(browser)
  if (person === 'platform') await login(page, PLATFORM_ADMIN.phone, PLATFORM_ADMIN.password)
  else await login(page, phone(person), PASSWORD)
  return page
}

/** Верхнє відкрите вікно (Drawer / Modal — role=dialog). */
export function dialog(page: Page): Locator {
  return page.getByRole('dialog').filter({ visible: true }).last()
}

/** Ant Design Select: відкрити поле й обрати варіант за текстом. */
export async function choose(page: Page, field: Locator, option: string | RegExp) {
  await field.click()
  const dropdown = page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)').last()
  await dropdown.locator('.ant-select-item-option').filter({ hasText: option }).first().click()
}

/** Варіанти відкритого Select (тексти). */
export async function options(page: Page, field: Locator): Promise<string[]> {
  await field.click()
  const dropdown = page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)').last()
  await expect(dropdown).toBeVisible()
  const texts = await dropdown.locator('.ant-select-item-option').allInnerTexts()
  await page.keyboard.press('Escape')
  return texts.map((s) => s.trim())
}

/** Ant Design DatePicker з форматом DD.MM.YYYY. */
export async function pickDate(field: Locator, date: Date) {
  const value = `${String(date.getDate()).padStart(2, '0')}.${String(date.getMonth() + 1).padStart(2, '0')}.${date.getFullYear()}`
  await field.click()
  await field.fill(value)
  await field.press('Enter')
}

/** Повідомлення Ant Design (message.success / error). */
export async function expectMessage(page: Page, text: string | RegExp) {
  await expect(page.locator('.ant-message').getByText(text).first()).toBeVisible()
}

/** Перше число минулого місяця (дата початку проєкту в сценарії). */
export function firstOfLastMonth(): Date {
  const now = new Date()
  return new Date(now.getFullYear(), now.getMonth() - 1, 1)
}

/** Підтвердити відкритий Popconfirm (кнопка «OK» у локалі Ant Design). */
export async function confirmPopover(page: Page) {
  await page.locator('.ant-popover:not(.ant-popover-hidden) .ant-popconfirm-buttons .ant-btn-primary').last().click()
}

/** Рядок таблиці або списку з текстом. */
export function row(scope: Page | Locator, text: string | RegExp): Locator {
  return scope.locator('.ant-table-row, .ant-list-item').filter({ hasText: text }).first()
}

/** Відкрити картку проєкту зі списку «Проєкти». */
export async function openProject(page: Page, code: string) {
  await page.goto('/projects')
  await page.getByRole('link', { name: code, exact: true }).click()
  await expect(page).toHaveURL(/\/projects\/[0-9a-f-]{36}/)
}

/** Вкладка за точною назвою. */
export async function tab(page: Page, name: string) {
  await page.getByRole('tab', { name, exact: true }).click()
}

/** Точна назва поля форми; необов'язкові поля antd дописують «(опціонально)». */
export function label(text: string): RegExp {
  const escaped = text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return new RegExp(`^${escaped}(\\s*\\(.+\\))?$`)
}

/** Кнопка за точним текстом; antd додає назву іконки попереду («plus Додати»). */
export function button(text: string): RegExp {
  const escaped = text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return new RegExp(`^([a-z-]+ )?${escaped}$`)
}

/**
 * Натиснути кнопку у вікні й дочекатися, поки закриється саме це вікно. Елемент фіксується ДО
 * натискання: dialog(page) — «останнє видиме», тож після закриття він перескочив би на шухляду під
 * вікном або не знайшов би нічого, якщо вікно закрилося швидше за перевірку (CI).
 */
export async function submitAndClose(scope: Locator, buttonName: string | RegExp): Promise<void> {
  const el = await scope.elementHandle()
  await scope.getByRole('button', { name: buttonName }).click()
  await expect.poll(async () => (el ? await el.isVisible().catch(() => false) : false)).toBe(false)
}
