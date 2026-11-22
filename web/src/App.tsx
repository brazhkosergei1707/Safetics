import { App as AntApp, ConfigProvider } from 'antd'
import enUS from 'antd/locale/en_US'
import ukUA from 'antd/locale/uk_UA'
import { useTranslation } from 'react-i18next'
import { createBrowserRouter, RouterProvider } from 'react-router'
import { AppLayout } from './layout/AppLayout'
import { RequireAuth } from './layout/RequireAuth'
import { AssetsPage } from './pages/assets/AssetsPage'
import { AuditLogPage } from './pages/AuditLogPage'
import { CustomersPage } from './pages/customers/CustomersPage'
import { DashboardPage } from './pages/DashboardPage'
import { LoginPage } from './pages/LoginPage'
import { PersonnelPage } from './pages/personnel/PersonnelPage'
import { PlaceholderPage } from './pages/PlaceholderPage'
import { ProjectDetailPage } from './pages/projects/ProjectDetailPage'
import { ProjectsPage } from './pages/projects/ProjectsPage'

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
      { path: 'projects', element: <ProjectsPage /> },
      { path: 'projects/:id', element: <ProjectDetailPage /> },
      { path: 'customers', element: <CustomersPage /> },
      { path: 'personnel', element: <PersonnelPage /> },
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
