import { Table, Typography } from 'antd'
import { useTranslation } from 'react-i18next'
import { type AuditEntry, useAuditLog } from '../api/audit'

export function AuditLogPage() {
  const { t, i18n } = useTranslation()
  const audit = useAuditLog()

  return (
    <>
      <Typography.Title level={2}>{t('nav.auditLog')}</Typography.Title>
      <Table<AuditEntry>
        rowKey="id"
        loading={audit.isPending}
        dataSource={audit.data ?? []}
        pagination={{ pageSize: 20 }}
        columns={[
          {
            title: t('audit.time'),
            dataIndex: 'createdAt',
            render: (value: string) => new Date(value).toLocaleString(i18n.language),
          },
          { title: t('audit.entity'), dataIndex: 'entity' },
          { title: t('audit.action'), dataIndex: 'action' },
          { title: t('audit.entityId'), dataIndex: 'entityId', ellipsis: true },
        ]}
      />
    </>
  )
}
