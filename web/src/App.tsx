import { ConfigProvider } from 'antd'
import enUS from 'antd/locale/en_US'
import ukUA from 'antd/locale/uk_UA'
import { useTranslation } from 'react-i18next'
import { createBrowserRouter, RouterProvider } from 'react-router'
import { AppLayout } from './layout/AppLayout'
import { DashboardPage } from './pages/DashboardPage'
import { PlaceholderPage } from './pages/PlaceholderPage'

const router = createBrowserRouter([
  {
    path: '/',
    element: <AppLayout />,
    children: [
      { index: true, element: <DashboardPage /> },
      { path: 'reports', element: <PlaceholderPage titleKey="nav.reports" /> },
      { path: 'stock', element: <PlaceholderPage titleKey="nav.stock" /> },
      { path: 'hse', element: <PlaceholderPage titleKey="nav.hse" /> },
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
      <RouterProvider router={router} />
    </ConfigProvider>
  )
}
