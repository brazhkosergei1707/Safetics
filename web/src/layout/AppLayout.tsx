import {
  AlertOutlined,
  AuditOutlined,
  DashboardOutlined,
  FileTextOutlined,
  InboxOutlined,
  LogoutOutlined,
} from '@ant-design/icons'
import { Button, Flex, Layout, Menu, Select, Typography } from 'antd'
import { useTranslation } from 'react-i18next'
import { Outlet, useLocation, useNavigate } from 'react-router'
import { hasPermission, useLogout, useSession, useSwitchCompany } from '../api/auth'
import { LANGUAGES } from '../i18n'

const { Header, Sider, Content } = Layout

export function AppLayout() {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const { pathname } = useLocation()
  const { data: session } = useSession()
  const switchCompany = useSwitchCompany()
  const logout = useLogout()

  const items = [
    { key: '/', icon: <DashboardOutlined />, label: t('nav.dashboard') },
    { key: '/reports', icon: <FileTextOutlined />, label: t('nav.reports') },
    { key: '/stock', icon: <InboxOutlined />, label: t('nav.stock') },
    { key: '/hse', icon: <AlertOutlined />, label: t('nav.hse') },
    ...(hasPermission(session, 'audit.view')
      ? [{ key: '/audit-log', icon: <AuditOutlined />, label: t('nav.auditLog') }]
      : []),
  ]

  const onLogout = () => logout.mutate(undefined, { onSettled: () => navigate('/login', { replace: true }) })

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider breakpoint="lg" collapsedWidth={0} theme="light">
        <Typography.Title level={4} style={{ padding: '16px 24px', margin: 0 }}>
          Safetics
        </Typography.Title>
        <Menu mode="inline" selectedKeys={[pathname]} items={items} onClick={({ key }) => navigate(key)} />
      </Sider>
      <Layout>
        <Header style={{ background: 'transparent', paddingInline: 24 }}>
          <Flex justify="flex-end" align="center" gap={12} style={{ height: '100%' }}>
            {session && session.companies.length > 1 ? (
              <Select
                value={session.company?.id}
                loading={switchCompany.isPending}
                onChange={(companyId) => switchCompany.mutate(companyId, { onSuccess: () => navigate('/') })}
                options={session.companies.map((c) => ({ value: c.id, label: c.name }))}
                style={{ minWidth: 200 }}
              />
            ) : (
              <Typography.Text strong>{session?.company?.name}</Typography.Text>
            )}
            <Typography.Text type="secondary">{session?.user.fullName}</Typography.Text>
            <Select
              value={i18n.language}
              onChange={(lng) => i18n.changeLanguage(lng)}
              options={LANGUAGES.map((l) => ({ value: l.code, label: l.label }))}
              style={{ width: 130 }}
            />
            <Button icon={<LogoutOutlined />} onClick={onLogout} loading={logout.isPending}>
              {t('auth.logout')}
            </Button>
          </Flex>
        </Header>
        <Content style={{ padding: 24 }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  )
}
