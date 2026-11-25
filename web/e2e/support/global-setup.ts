import { newRun } from './state.ts'

/** Новий прогін: унікальний номер для телефонів і назв (база e2e накопичує дані між прогонами). */
export default function globalSetup() {
  const run = newRun()
  console.log(`E2E прогін ${run.run}: компанія «${run.companyName}»`)
}
