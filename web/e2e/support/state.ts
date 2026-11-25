import fs from 'node:fs'
import path from 'node:path'

/**
 * Дані наскрізного прогону: номер прогону (унікальні телефони й назви) і те, що створили
 * попередні розділи. Зберігається в e2e/.state.json між файлами тестів.
 */
export interface RunState {
  run: string
  companyName: string
  customerName: string
  otherCustomerName: string
  projectCode: string
  [key: string]: string
}

const FILE = path.join(import.meta.dirname, '..', '.state.json')

export function newRun(): RunState {
  const run = String(Date.now() % 10_000).padStart(4, '0')
  const state: RunState = {
    run,
    companyName: `E2E Drilling ${run}`,
    customerName: `E2E Mining ${run}`,
    otherCustomerName: `E2E Other ${run}`,
    projectCode: `E2E-${run}`,
  }
  fs.writeFileSync(FILE, JSON.stringify(state, null, 2))
  return state
}

export function state(): RunState {
  return JSON.parse(fs.readFileSync(FILE, 'utf8')) as RunState
}

export function save(values: Record<string, string>) {
  fs.writeFileSync(FILE, JSON.stringify({ ...state(), ...values }, null, 2))
}

/** Учасники сценарію (табл. «Учасники» регресійного плану); телефон +380 63 <прогін> <номер>. */
export const PEOPLE = {
  // Імена не мають містити одне одного: фільтр рядків hasText — підрядок без урахування регістру
  admin: { no: '001', name: 'Андрій Адміненко' },
  head: { no: '002', name: 'Ганна Головенко' },
  manager: { no: '003', name: 'Микола Мененко' },
  supervisor: { no: '004', name: 'Сергій Суперенко' },
  driller: { no: '005', name: 'Богдан Буренко' },
  helper: { no: '', name: 'Петро Помбуренко' },
  clientRep: { no: '006', name: 'Галина Геоленко' },
  clientManager: { no: '007', name: 'Зоряна Замовенко' },
} as const

export type Person = keyof typeof PEOPLE

export function phone(person: Person): string {
  return `+38063${state().run}${PEOPLE[person].no}`
}

export const PASSWORD = 'e2e-pass-1'
export const PLATFORM_ADMIN = { phone: '+380500000000', password: 'safetics-dev' }
