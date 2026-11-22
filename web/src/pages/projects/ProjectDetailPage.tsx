import { ArrowLeftOutlined } from '@ant-design/icons'
import { App, Button, Flex, Popconfirm, Result, Spin, Tabs, Tag, Typography } from 'antd'
import { useTranslation } from 'react-i18next'
import { Link, useParams } from 'react-router'
import { hasPermission, useSession } from '../../api/auth'
import { ApiError } from '../../api/client'
import { errorMessage } from '../../api/formErrors'
import { useCloseProject, useProject } from '../../api/projects'
import { ProjectAccessTab } from './ProjectAccessTab'
import { ProjectGeneralTab } from './ProjectGeneralTab'
import { ShiftSchedulesTab } from './ShiftSchedulesTab'

export function ProjectDetailPage() {
  const { t } = useTranslation()
  const { message } = App.useApp()
  const { id } = useParams()
  const { data: session } = useSession()
  const project = useProject(id)
  const close = useCloseProject()
  const canManage = hasPermission(session, 'project.manage')

  if (project.isPending) return <Spin />
  if (project.isError || !project.data) {
    const notFound = project.error instanceof ApiError && project.error.status === 404
    return (
      <Result
        status={notFound ? '404' : 'error'}
        title={notFound ? t('projects.notFound') : t('errors.serverUnavailable')}
        extra={<Link to="/projects">{t('projects.backToList')}</Link>}
      />
    )
  }

  const p = project.data
  const closed = p.status === 'closed'

  return (
    <>
      <Link to="/projects">
        <ArrowLeftOutlined /> {t('nav.projects')}
      </Link>
      <Flex justify="space-between" align="center" style={{ margin: '8px 0 16px' }} wrap gap={8}>
        <Flex align="center" gap={12}>
          <Typography.Title level={2} style={{ margin: 0 }}>
            {p.code} · {p.name}
          </Typography.Title>
          <Tag color={closed ? undefined : 'green'}>{t(`projects.status.${p.status}`)}</Tag>
        </Flex>
        {canManage && (
          <Popconfirm
            title={closed ? t('projects.reopenConfirm') : t('projects.closeConfirm')}
            onConfirm={() =>
              close.mutate(
                { id: p.id, reopen: closed },
                { onError: (error) => message.error(errorMessage(error, t)) },
              )
            }
          >
            <Button loading={close.isPending}>{closed ? t('projects.reopen') : t('projects.close')}</Button>
          </Popconfirm>
        )}
      </Flex>

      <Tabs
        items={[
          { key: 'general', label: t('projects.tabs.general'), children: <ProjectGeneralTab project={p} /> },
          { key: 'shifts', label: t('projects.tabs.shifts'), children: <ShiftSchedulesTab project={p} /> },
          ...(canManage
            ? [{ key: 'access', label: t('projects.tabs.access'), children: <ProjectAccessTab project={p} /> }]
            : []),
        ]}
      />
    </>
  )
}
