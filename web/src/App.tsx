import { App as AntApp, ConfigProvider } from 'antd'
import enUS from 'antd/locale/en_US'
import ukUA from 'antd/locale/uk_UA'
import { useTranslation } from 'react-i18next'
import { createBrowserRouter, RouterProvider } from 'react-router'
import { AppLayout } from './layout/AppLayout'
import { RequireAuth } from './layout/RequireAuth'
import { AssetsPage } from './pages/assets/AssetsPage'
import { AuditLogPage } from './pages/AuditLogPage'
import { DashboardPage } from './pages/DashboardPage'
import { LoginPage } from './pages/LoginPage'
import { PlaceholderPage } from './pages/PlaceholderPage'

const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  {
    path: '/',
    element: (
      <RequireAuth>
        <AppLayout />
      </RequireAuth>
    ),
    children: [
      { index: true, element: <DashboardPage /> },
      { path: 'assets', element: <AssetsPage /> },
      { path: 'reports', element: <PlaceholderPage titleKey="nav.reports" /> },
      { path: 'stock', element: <PlaceholderPage titleKey="nav.stock" /> },
      { path: 'hse', element: <PlaceholderPage titleKey="nav.hse" /> },
      { path: 'audit-log', element: <AuditLogPage /> },
    ],
  },
])

export function App() {
  const { i18n } = useTranslation()

  return (
    <ConfigProvider
      locale={i18n.language === 'uk' ? ukUA : enUS}
      theme={{ token: { colorPrimary: '#c2410c', borderRadius: 6 } }}
    >
      {/* AntApp дає message/modal з поточною темою через App.useApp() */}
      <AntApp>
        <RouterProvider router={router} />
      </AntApp>
    </ConfigProvider>
  )
}
