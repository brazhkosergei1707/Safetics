import { PlusOutlined } from '@ant-design/icons'
import { Button, Flex, Input, Segmented, Table, Tag, Typography } from 'antd'
import dayjs from 'dayjs'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { hasPermission, useSession } from '../../api/auth'
import { type Project, shiftsSummary, useProjects } from '../../api/projects'
import { ProjectCreateDrawer } from './ProjectCreateDrawer'

export function ProjectsPage() {
  const { t } = useTranslation()
  const { data: session } = useSession()
  const [status, setStatus] = useState<string>('active')
  const [query, setQuery] = useState('')
  const [creating, setCreating] = useState(false)
  const projects = useProjects({ status: status === 'all' ? undefined : status, q: query })
  const canManage = hasPermission(session, 'project.manage')

  return (
    <>
      <Flex justify="space-between" align="center" style={{ marginBottom: 16 }}>
        <Typography.Title level={2} style={{ margin: 0 }}>
          {t('nav.projects')}
        </Typography.Title>
        {canManage && (
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreating(true)}>
            {t('projects.add')}
          </Button>
        )}
      </Flex>

      <Flex gap={12} wrap style={{ marginBottom: 16 }}>
        <Segmented
          value={status}
          onChange={setStatus}
          options={[
            { value: 'active', label: t('projects.status.active') },
            { value: 'closed', label: t('projects.status.closed') },
            { value: 'all', label: t('projects.all') },
          ]}
        />
        <Input.Search allowClear placeholder={t('projects.search')} onSearch={setQuery} style={{ maxWidth: 320 }} />
      </Flex>

      <Table<Project>
        rowKey="id"
        size="middle"
        loading={projects.isPending}
        dataSource={projects.data ?? []}
        pagination={{ pageSize: 50, hideOnSinglePage: true }}
        scroll={{ x: 'max-content' }}
        locale={{ emptyText: t('projects.empty') }}
        columns={[
          {
            title: t('projects.code'),
            dataIndex: 'code',
            render: (code: string, project) => <Link to={`/projects/${project.id}`}>{code}</Link>,
          },
          { title: t('projects.name'), dataIndex: 'name' },
          { title: t('projects.customer'), dataIndex: 'customerName' },
          {
            title: t('projects.location'),
            key: 'location',
            render: (_, p) => [p.location, p.country].filter(Boolean).join(', '),
          },
          {
            title: t('projects.shifts'),
            key: 'shifts',
            render: (_, p) => shiftsSummary(p.shifts),
          },
          {
            title: t('projects.dates'),
            key: 'dates',
            render: (_, p) =>
              `${dayjs(p.startDate).format('DD.MM.YYYY')} – ${p.endDate ? dayjs(p.endDate).format('DD.MM.YYYY') : '…'}`,
          },
          {
            title: t('projects.statusLabel'),
            dataIndex: 'status',
            render: (value: Project['status']) => (
              <Tag color={value === 'active' ? 'green' : undefined}>{t(`projects.status.${value}`)}</Tag>
            ),
          },
        ]}
      />

      <ProjectCreateDrawer open={creating} onClose={() => setCreating(false)} />
    </>
  )
}
