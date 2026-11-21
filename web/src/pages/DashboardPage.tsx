import { Card, Descriptions, Flex, Tag, Typography } from 'antd'
import { useTranslation } from 'react-i18next'
import { useSession } from '../api/auth'
import { useBackendHealth } from '../api/health'

export function DashboardPage() {
  const { t } = useTranslation()
  const health = useBackendHealth()
  const { data: session } = useSession()

  const status = health.isPending
    ? <Tag>{t('health.checking')}</Tag>
    : health.isError
      ? <Tag color="red">{t('health.unreachable')}</Tag>
      : <Tag color={health.data.status === 'UP' ? 'green' : 'orange'}>{health.data.status}</Tag>

  const role = session?.company?.role

  return (
    <>
      <Typography.Title level={2}>{t('nav.dashboard')}</Typography.Title>
      <Flex gap={16} wrap>
        <Card title={t('session.title')} style={{ width: 420 }}>
          <Descriptions column={1} size="small">
            <Descriptions.Item label={t('session.user')}>{session?.user.fullName}</Descriptions.Item>
            <Descriptions.Item label={t('session.company')}>{session?.company?.name}</Descriptions.Item>
            <Descriptions.Item label={t('session.role')}>{role ? t(`roles.${role}`, role) : '—'}</Descriptions.Item>
            <Descriptions.Item label={t('session.permissions')}>{session?.permissions.length ?? 0}</Descriptions.Item>
          </Descriptions>
        </Card>
        <Card title={t('health.title')} style={{ width: 320 }}>
          <Flex gap={8} align="center">
            <span>{t('health.backend')}:</span>
            {status}
          </Flex>
        </Card>
      </Flex>
    </>
  )
}
