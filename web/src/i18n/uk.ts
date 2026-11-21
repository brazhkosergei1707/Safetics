import type en from './en'

// Тип береться з англійського файлу: пропущений ключ дасть помилку компіляції.
const uk: typeof en = {
  nav: {
    dashboard: 'Огляд',
    reports: 'Змінні рапорти',
    stock: 'Склад',
    hse: 'Безпека (HSE)',
    auditLog: 'Журнал змін',
  },
  login: {
    phone: 'Телефон',
    password: 'Пароль',
    submit: 'Увійти',
    phoneRequired: 'Введіть номер телефону',
    passwordRequired: 'Введіть пароль',
    invalidCredentials: 'Неправильний телефон або пароль',
  },
  auth: {
    logout: 'Вийти',
  },
  company: {
    choose: 'Оберіть компанію',
    select: 'Відкрити',
    none: 'Ви ще не працюєте в жодній активній компанії',
  },
  session: {
    title: 'Сесія',
    user: 'Користувач',
    company: 'Компанія',
    role: 'Роль',
    permissions: 'Дозволів',
  },
  roles: {
    owner: 'Власник',
    admin: 'Адмін',
    manager: 'Менеджер',
    worker: 'Виконавець',
    client_rep: 'Представник замовника',
  },
  audit: {
    time: 'Час',
    entity: "Об'єкт",
    action: 'Дія',
    entityId: 'ID',
  },
  health: {
    title: 'Стан системи',
    backend: 'Сервер',
    checking: 'Перевірка…',
    unreachable: 'Недоступний',
  },
  errors: {
    serverUnavailable: 'Сервер недоступний. Спробуйте пізніше.',
  },
  common: {
    comingSoon: 'Скоро буде',
    retry: 'Спробувати ще',
  },
}

export default uk
