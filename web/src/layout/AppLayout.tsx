import {
  AlertOutlined,
  AppstoreOutlined,
  AuditOutlined,
  DashboardOutlined,
  FileTextOutlined,
  InboxOutlined,
  LogoutOutlined,
  ProjectOutlined,
  CheckSquareOutlined,
  FileDoneOutlined,
  SolutionOutlined,
  TeamOutlined,
  UserOutlined,
  GlobalOutlined,
} from '@ant-design/icons'
import { Button, Flex, Layout, Menu, Select, Typography } from 'antd'
import { useTranslation } from 'react-i18next'
import { Outlet, useLocation, useNavigate } from 'react-router'
import { AlertsBell } from './AlertsBell'
import { hasPermission, useLogout, useSession, useSwitchCompany } from '../api/auth'
import { LANGUAGES } from '../i18n'
import { localized } from '../i18n/localized'

const { Header, Sider, Content } = Layout

export function AppLayout() {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const { pathname } = useLocation()
  const { data: session } = useSession()
  const switchCompany = useSwitchCompany()
  const logout = useLogout()

  const platformItem = session?.platformAdmin
    ? [{ key: '/platform', icon: <GlobalOutlined />, label: t('nav.platform') }]
    : []
  const companyItems = [
    { key: '/', icon: <DashboardOutlined />, label: t('nav.dashboard') },
    ...(hasPermission(session, 'task.view')
      ? [{ key: '/my-tasks', icon: <CheckSquareOutlined />, label: t('nav.myTasks') }]
      : []),
    { key: '/projects', icon: <ProjectOutlined />, label: t('nav.projects') },
    ...(hasPermission(session, 'client.view')
      ? [{ key: '/customers', icon: <TeamOutlined />, label: t('nav.customers') }]
      : []),
    ...(hasPermission(session, 'personnel.manage') || hasPermission(session, 'catalog.manage')
      ? [{ key: '/personnel', icon: <SolutionOutlined />, label: t('nav.personnel') }]
      : []),
    ...(hasPermission(session, 'asset.view')
      ? [{ key: '/assets', icon: <AppstoreOutlined />, label: t('nav.assets') }]
      : []),
    ...(hasPermission(session, 'report.review') || hasPermission(session, 'monthly_report.approve')
      ? [{ key: '/review', icon: <FileDoneOutlined />, label: t('nav.review') }]
      : []),
    { key: '/reports', icon: <FileTextOutlined />, label: t('nav.reports') },
    { key: '/stock', icon: <InboxOutlined />, label: t('nav.stock') },
    { key: '/hse', icon: <AlertOutlined />, label: t('nav.hse') },
    ...(hasPermission(session, 'staff.manage')
      ? [{ key: '/users', icon: <UserOutlined />, label: t('nav.users') }]
      : []),
    ...(hasPermission(session, 'audit.view')
      ? [{ key: '/audit-log', icon: <AuditOutlined />, label: t('nav.auditLog') }]
      : []),
  ]
  // Без обраної компанії (адмін платформи) — лише «Компанії»
  const items = session?.company ? [...companyItems, ...platformItem] : platformItem

  const onLogout = () => logout.mutate(undefined, { onSettled: () => navigate('/login', { replace: true }) })

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider breakpoint="lg" collapsedWidth={0} theme="light">
        <Typography.Title level={4} style={{ padding: '16px 24px', margin: 0 }}>
          Safetics
        </Typography.Title>
        <Menu mode="inline" selectedKeys={[pathname.startsWith('/projects') ? '/projects' : pathname]} items={items} onClick={({ key }) => navigate(key)} />
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
            <Flex vertical align="flex-end" style={{ lineHeight: 1.3 }}>
              <Typography.Text>{session?.user.fullName}</Typography.Text>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {[
                  session?.company?.role ? t(`roles.${session.company.role}`, { defaultValue: session.company.role }) : null,
                  session?.position ? localized(session.position, i18n.language) : null,
                  session?.platformAdmin ? t('platform.platformAdmin') : null,
                ]
                  .filter(Boolean)
                  .join(' · ')}
              </Typography.Text>
            </Flex>
            {session?.company && <AlertsBell />}
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
