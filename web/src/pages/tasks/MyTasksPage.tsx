import { Flex, Switch, Table, Tag, Typography } from 'antd'
import dayjs from 'dayjs'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { useProject } from '../../api/projects'
import { CATEGORY_COLORS, type Task, useMyTasks, useTaskTypes } from '../../api/tasks'
import { localized } from '../../i18n/localized'
import { TaskDrawer } from './TaskDrawer'
import type { TaskDrawerState } from './ProjectTasksTab'

/** Задачі, призначені мені, з усіх проєктів. */
export function MyTasksPage() {
  const { t, i18n } = useTranslation()
  const lang = i18n.language
  const [includeClosed, setIncludeClosed] = useState(false)
  const tasks = useMyTasks(includeClosed)
  const types = useTaskTypes(true)
  const [drawer, setDrawer] = useState<TaskDrawerState & { projectId?: string }>({ open: false })
  const project = useProject(drawer.projectId)
  const typeById = new Map((types.data ?? []).map((tt) => [tt.id, tt]))

  const statusTag = (task: Task) => {
    const status = typeById.get(task.typeId)?.statuses.find((s) => s.code === task.status)
    return <Tag color={CATEGORY_COLORS[task.statusCategory]}>{localized(status?.label, lang) || task.status}</Tag>
  }

  return (
    <>
      <Flex justify="space-between" align="center" style={{ marginBottom: 16 }}>
        <Typography.Title level={2} style={{ margin: 0 }}>
          {t('nav.myTasks')}
        </Typography.Title>
        <Flex gap={8} align="center">
          <Switch size="small" checked={includeClosed} onChange={setIncludeClosed} />
          {t('tasks.showClosed')}
        </Flex>
      </Flex>
      <Table<Task>
        rowKey="id"
        size="middle"
        loading={tasks.isPending}
        dataSource={tasks.data ?? []}
        pagination={{ pageSize: 50, hideOnSinglePage: true }}
        locale={{ emptyText: t('tasks.noMine') }}
        columns={[
          {
            title: t('tasks.title'),
            key: 'title',
            render: (_, task) => (
              <Flex gap={6}>
                <Typography.Text type="secondary">#{task.number}</Typography.Text>
                <Typography.Link onClick={() => setDrawer({ open: true, taskId: task.id, projectId: task.projectId })}>
                  {task.title}
                </Typography.Link>
              </Flex>
            ),
          },
          {
            title: t('nav.projects'),
            key: 'project',
            render: (_, task) => <Link to={`/projects/${task.projectId}`}>{task.projectCode}</Link>,
          },
          { title: t('projects.statusLabel'), key: 'status', render: (_, task) => statusTag(task) },
          { title: t('tasks.subject'), key: 'subject', render: (_, task) => task.serviceCode ?? task.assetCode ?? '' },
          {
            title: t('services.plannedEnd'),
            key: 'plannedEnd',
            render: (_, task) => {
              if (!task.plannedEnd) return ''
              const overdue = task.statusCategory !== 'done' && task.statusCategory !== 'cancelled' && dayjs(task.plannedEnd).isBefore(dayjs(), 'day')
              return <Typography.Text type={overdue ? 'danger' : undefined}>{dayjs(task.plannedEnd).format('DD.MM.YYYY')}</Typography.Text>
            },
          },
        ]}
      />
      {project.data && (
        <TaskDrawer
          open={drawer.open}
          project={project.data}
          types={types.data ?? []}
          taskId={drawer.taskId}
          newType={drawer.newType}
          parentId={drawer.parentId}
          onClose={() => setDrawer({ open: false })}
          onOpenTask={(id) => setDrawer({ open: true, taskId: id, projectId: drawer.projectId })}
          onAddChild={(parent, childType) =>
            setDrawer({ open: true, parentId: parent.id, newType: childType, projectId: parent.projectId })
          }
        />
      )}
    </>
  )
}
