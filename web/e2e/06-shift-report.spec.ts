import { type APIRequestContext, expect, test } from '@playwright/test'
import { t } from './support/i18n.ts'
import { state } from './support/state.ts'
import {
  clickAndConfirm,
  day,
  expectStatus,
  fillTimeRow,
  iso,
  openMyTask,
  rowTask,
  timeRow,
} from './support/shifts.ts'
import { as, choose, expectMessage } from './support/ui.ts'

// Розділ 6 регресійного плану: змінний рапорт з інтервалами буріння в журналі часу.
// День 3 — повний рапорт у вебі, 4 — без чек-листа, 5 — у вебі із заповненням з попередньої зміни,
// 6 — через API (дані для перевірки замовником і місячного звіту).
test.describe.configure({ mode: 'serial' })

test('6.1 рапорт не подається без чек-листа; чернетку зберегти можна', async ({ browser }) => {
  const page = await as(browser, 'driller')
  const report = await openMyTask(page, 'Змінний рапорт', day(4))
  await fillTimeRow(page, timeRow(report, 0), { to: '07:00', code: 'MAINT' })
  await report.getByRole('button', { name: t('reports.saveDraft') }).click()
  await expectMessage(page, t('common.saved'))
  await clickAndConfirm(report, t('reports.submit'))
  await expectMessage(page, t('apiErrors.checklist_required'))
  await page.context().close()
})

test('6.2–6.8 журнал часу з інтервалами: задача й «від» підставляються, переїзд на іншу свердловину', async ({ browser }) => {
  const s = state()
  const page = await as(browser, 'driller')
  const report = await openMyTask(page, 'Змінний рапорт', day(3))

  // 6.2 Проміжок у журналі — помилка, подати не можна
  await fillTimeRow(page, timeRow(report, 0), { to: '12:00', code: 'MAINT' })
  await report.getByRole('button', { name: t('reports.saveDraft') }).click()
  await expect(report).toContainText(t('reports.errorsTitle'))
  await expect(report.getByRole('button', { name: t('reports.submit') })).toBeDisabled()

  // 6.3 Код буріння: задача свердловини підставилась, під рядком — інтервал
  const first = timeRow(report, 0)
  await fillTimeRow(page, first, { code: 'DRL' })
  await expect(rowTask(first)).toContainText(s.hole1)
  await expect(report).not.toContainText(t('reports.intervals'))
  // 6.4 Інтервал; другий рядок буріння — «від» з попереднього «до»
  await fillTimeRow(page, first, { depthFrom: 0, depthTo: 35.5, core: 34 })
  await expect(first).toContainText('35.5 m · 95.8%')
  await report.getByRole('button', { name: t('reports.addTime') }).click()
  const second = timeRow(report, 1)
  await expect(second.getByPlaceholder(t('reports.depthFrom'))).toHaveAttribute('aria-valuenow', '35.5')
  await fillTimeRow(page, second, { to: '15:00', depthTo: 50, core: 14 })

  // 6.6–6.7 Переїзд на TM-BH2: рядок буріння без глибин — попередження
  await report.getByRole('button', { name: t('reports.addTime') }).click()
  const third = timeRow(report, 2)
  await choose(page, third.getByRole('combobox').nth(1), s.hole2)
  await third.getByPlaceholder(t('reports.depthFrom')).fill('')
  await fillTimeRow(page, third, { to: '17:00' })
  await report.getByRole('button', { name: t('reports.addTime') }).click()
  await fillTimeRow(page, timeRow(report, 3), { to: '19:00', code: 'MAINT' })
  await report.getByRole('button', { name: t('reports.saveDraft') }).click()
  await expectMessage(page, t('common.saved'))
  await expect(report).toContainText('без інтервалу')

  await fillTimeRow(page, third, { depthFrom: 0, depthTo: 10, core: 9.5 })
  await report.getByRole('button', { name: t('reports.saveDraft') }).click()
  await expect(report).not.toContainText('без інтервалу')
  await expect(report).not.toContainText(t('reports.errorsTitle'))
  // Пробурено 35,5 + 14,5 + 10
  await expect(report.locator('.ant-statistic').first()).toContainText('60')

  // 6.8 Подати
  await clickAndConfirm(report, t('reports.submit'))
  await expectMessage(page, t('reports.submitted'))
  await expectStatus(report, t('reports.status.submitted'))
  await page.context().close()
})

test('6.9 наступна зміна: задача буріння й «від» — з попереднього рапорту', async ({ browser }) => {
  const s = state()
  const page = await as(browser, 'driller')
  await passChecklist(page.request, day(5))
  const report = await openMyTask(page, 'Змінний рапорт', day(5))
  const first = timeRow(report, 0)
  await fillTimeRow(page, first, { to: '07:00', code: 'DRL' })
  const task = await rowTask(first).innerText()
  const expected = task.includes(s.hole1) ? '50' : '10'
  await expect(first.getByPlaceholder(t('reports.depthFrom'))).toHaveAttribute('aria-valuenow', expected)
  await fillTimeRow(page, first, { depthTo: Number(expected) + 12, core: 11.5 })
  await report.getByRole('button', { name: t('reports.saveDraft') }).click()
  await expectMessage(page, t('common.saved'))
  await clickAndConfirm(report, t('reports.submit'))
  await expectStatus(report, t('reports.status.submitted'))
  await page.context().close()

  // День 6 — через API, щоб у замовника й у місячному звіті було кілька рапортів
  const api = await as(browser, 'driller')
  await submitReportViaApi(api.request, day(6), 9)
  await api.context().close()
})

// ---------------------------------------------------------------- API-допоміжні (дані, не перевірки інтерфейсу)

interface BoardShift {
  id: string
  date: string
  shiftCode: string
  assetCode: string
}

async function findShift(request: APIRequestContext, date: Date): Promise<string> {
  const projects = (await (await request.get('/api/v1/projects')).json()) as { id: string; code: string }[]
  const project = projects.find((p) => p.code === state().projectCode)!
  const board = await (
    await request.get(`/api/v1/projects/${project.id}/shift-board?from=${iso(date)}&to=${iso(date)}`)
  ).json()
  const shift = (board.shifts as BoardShift[]).find((x) => x.shiftCode === 'day' && x.assetCode === state().rig)
  expect(shift, `зміна ${iso(date)}`).toBeTruthy()
  return shift!.id
}

/** Чек-лист зміни: усі пункти «Так». */
export async function passChecklist(request: APIRequestContext, date: Date) {
  const shiftId = await findShift(request, date)
  const checklist = await (await request.get(`/api/v1/shifts/${shiftId}/checklist`)).json()
  const response = await request.post(`/api/v1/shifts/${shiftId}/checklist`, {
    data: { answers: (checklist.items as { key: string }[]).map((i) => ({ key: i.key, result: 'pass' })) },
  })
  expect(response.ok(), await response.text()).toBeTruthy()
}

/** Рапорт на всю зміну: буріння по першій свердловині на `meters` м від останнього «до», подати. */
async function submitReportViaApi(request: APIRequestContext, date: Date, meters: number) {
  await passChecklist(request, date)
  const shiftId = await findShift(request, date)
  const report = await (await request.get(`/api/v1/shifts/${shiftId}/report`)).json()
  const codes = (await (await request.get('/api/v1/activity-codes')).json()) as { id: string; code: string }[]
  const drl = codes.find((c) => c.code === 'DRL')!.id
  const start = (report.prefill as { taskId?: string; depthFrom: number }[]).find((p) => p.taskId)!
  const body = {
    version: report.version,
    times: [
      {
        startMinute: 0,
        endMinute: report.durationMinutes,
        activityCodeId: drl,
        taskId: start.taskId,
        interval: { depthFrom: start.depthFrom, depthTo: start.depthFrom + meters, coreSize: 'HQ', coreLength: meters - 0.5 },
      },
    ],
    quantities: [],
    consumables: [],
    meters: [],
  }
  const saved = await request.put(`/api/v1/shifts/${shiftId}/report`, { data: body })
  expect(saved.ok(), await saved.text()).toBeTruthy()
  const version = (await saved.json()).version
  const submitted = await request.post(`/api/v1/shifts/${shiftId}/report/submit`, { data: { version } })
  expect(submitted.ok(), await submitted.text()).toBeTruthy()
}
