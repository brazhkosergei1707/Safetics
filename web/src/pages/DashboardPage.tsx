import { Card, Space, Tag, Typography } from 'antd'
import { useTranslation } from 'react-i18next'
import { useBackendHealth } from '../api/health'

export function DashboardPage() {
  const { t } = useTranslation()
  const health = useBackendHealth()

  const status = health.isPending
    ? <Tag>{t('health.checking')}</Tag>
    : health.isError
      ? <Tag color="red">{t('health.unreachable')}</Tag>
      : <Tag color={health.data.status === 'UP' ? 'green' : 'orange'}>{health.data.status}</Tag>

  return (
    <Space orientation="vertical" size="large" style={{ width: '100%' }}>
      <Typography.Title level={2}>{t('nav.dashboard')}</Typography.Title>
      <Card title={t('health.title')} style={{ maxWidth: 420 }}>
        <Space>
          <span>{t('health.backend')}:</span>
          {status}
        </Space>
      </Card>
    </Space>
  )
}
