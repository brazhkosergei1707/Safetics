import type en from './en'

// Тип береться з англійського файлу: пропущений ключ дасть помилку компіляції.
const uk: typeof en = {
  nav: {
    dashboard: 'Огляд',
    reports: 'Змінні рапорти',
    stock: 'Склад',
    hse: 'Безпека (HSE)',
  },
  health: {
    title: 'Стан системи',
    backend: 'Сервер',
    checking: 'Перевірка…',
    unreachable: 'Недоступний',
  },
  common: {
    comingSoon: 'Скоро буде',
  },
}

export default uk
