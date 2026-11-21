import i18n from 'i18next'
import { initReactI18next } from 'react-i18next'
import en from './en'
import uk from './uk'

// Нові мови (pt, fr) додаються тут і окремим файлом перекладу.
export const LANGUAGES = [
  { code: 'en', label: 'English' },
  { code: 'uk', label: 'Українська' },
] as const

const STORAGE_KEY = 'safetics.lang'

function initialLanguage(): string {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    if (saved) return saved
  } catch {
    // localStorage може бути недоступний — тоді мова браузера
  }
  return navigator.language.startsWith('uk') ? 'uk' : 'en'
}

void i18n.use(initReactI18next).init({
  resources: { en: { translation: en }, uk: { translation: uk } },
  lng: initialLanguage(),
  fallbackLng: 'en',
  interpolation: { escapeValue: false },
})

i18n.on('languageChanged', (lng) => {
  try {
    localStorage.setItem(STORAGE_KEY, lng)
  } catch {
    // ігноруємо
  }
  document.documentElement.lang = lng
})

export default i18n
