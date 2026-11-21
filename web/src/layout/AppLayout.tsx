import {
  AlertOutlined,
  DashboardOutlined,
  FileTextOutlined,
  InboxOutlined,
} from '@ant-design/icons'
import { Layout, Menu, Select, Typography } from 'antd'
import { useTranslation } from 'react-i18next'
import { Outlet, useLocation, useNavigate } from 'react-router'
import { LANGUAGES } from '../i18n'

const { Header, Sider, Content } = Layout

export function AppLayout() {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const { pathname } = useLocation()

  const items = [
    { key: '/', icon: <DashboardOutlined />, label: t('nav.dashboard') },
    { key: '/reports', icon: <FileTextOutlined />, label: t('nav.reports') },
    { key: '/stock', icon: <InboxOutlined />, label: t('nav.stock') },
    { key: '/hse', icon: <AlertOutlined />, label: t('nav.hse') },
  ]

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider breakpoint="lg" collapsedWidth={0} theme="light">
        <Typography.Title level={4} style={{ padding: '16px 24px', margin: 0 }}>
          Safetics
        </Typography.Title>
        <Menu
          mode="inline"
          selectedKeys={[pathname]}
          items={items}
          onClick={({ key }) => navigate(key)}
        />
      </Sider>
      <Layout>
        <Header
          style={{
            background: 'transparent',
            display: 'flex',
            justifyContent: 'flex-end',
            alignItems: 'center',
          }}
        >
          <Select
            value={i18n.language}
            onChange={(lng) => i18n.changeLanguage(lng)}
            options={LANGUAGES.map((l) => ({ value: l.code, label: l.label }))}
            style={{ width: 140 }}
          />
        </Header>
        <Content style={{ padding: 24 }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  )
}
