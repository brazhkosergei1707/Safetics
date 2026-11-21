// Генерує типи API з опису бекенду.
// 1. Бере /v3/api-docs із запущеного сервера (профіль dev) і зберігає як openapi.json (читабельно, для diff у git).
// 2. Генерує src/api/schema.d.ts через openapi-typescript.
//
// openapi-typescript поки потребує TypeScript 5, а проєкт на TypeScript 7,
// тому він запускається через npx з окремим TypeScript 5 і не входить у залежності проєкту.
import { execFileSync } from 'node:child_process'
import { writeFileSync } from 'node:fs'

const url = process.env.API_DOCS_URL ?? 'http://localhost:8080/v3/api-docs'

let response
try {
  response = await fetch(url)
} catch {
  console.error(`Не вдалося з'єднатися з ${url}. Запустіть сервер: cd ../server && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`)
  process.exit(1)
}
if (!response.ok) {
  console.error(`${url} повернув ${response.status}. Чи запущено сервер з профілем dev?`)
  process.exit(1)
}

const spec = await response.json()
writeFileSync('openapi.json', JSON.stringify(spec, null, 2) + '\n')
console.log(`openapi.json: ${Object.keys(spec.paths ?? {}).length} шляхів`)

execFileSync(
  'npx',
  ['-y', '-p', 'typescript@5', '-p', 'openapi-typescript@7', 'openapi-typescript', 'openapi.json', '-o', 'src/api/schema.d.ts'],
  { stdio: 'inherit' },
)
