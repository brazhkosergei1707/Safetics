import { defineConfig, devices } from '@playwright/test'

// Автотести інтерфейсу (регресійний план claude/04-regression-test-plan.md).
// npm run e2e — підніме базу e2e (5434), сервер профілю e2e (8081) і Vite (5174), якщо вони ще не працюють.
const CI = !!process.env.CI

export default defineConfig({
  testDir: './e2e',
  // Сценарій наскрізний: розділи йдуть по черзі й передають дані через e2e/.state.json
  fullyParallel: false,
  workers: 1,
  // Без повторів: кроки залежать один від одного, повтор створив би дублікати
  retries: 0,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  reporter: CI ? [['list'], ['html', { open: 'never' }], ['github']] : [['list'], ['html', { open: 'never' }]],
  globalSetup: './e2e/support/global-setup.ts',
  use: {
    baseURL: 'http://localhost:5174',
    locale: 'uk-UA',
    timezoneId: 'Europe/Kyiv',
    trace: 'retain-on-failure',
    video: 'retain-on-failure',
    screenshot: 'only-on-failure',
    actionTimeout: 15_000,
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], viewport: { width: 1600, height: 1000 } } }],
  webServer: [
    {
      command: './mvnw -q spring-boot:run -Dspring-boot.run.profiles=e2e',
      cwd: '../server',
      url: 'http://localhost:8081/actuator/health',
      timeout: 300_000,
      reuseExistingServer: !CI,
      stdout: 'ignore',
      stderr: 'pipe',
    },
    {
      command: 'npx vite --port 5174 --strictPort',
      url: 'http://localhost:5174',
      env: { SAFETICS_API: 'http://localhost:8081' },
      timeout: 60_000,
      reuseExistingServer: !CI,
    },
  ],
})
