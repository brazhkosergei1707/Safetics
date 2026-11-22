import { DownOutlined, LockOutlined, PlusOutlined } from '@ant-design/icons'
import { Button, Dropdown, Flex, Segmented, Select, Table, Tag, Typography } from 'antd'
import dayjs from 'dayjs'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { Project } from '../../api/projects'
import { CATEGORY_COLORS, type Task, type TaskType, useProjectTasks, useTaskTypes } from '../../api/tasks'
import { localized } from '../../i18n/localized'
import { TaskDrawer } from './TaskDrawer'
import { useTaskRights } from './taskRights'

type Filter = 'open' | 'done' | 'cancelled' | 'all'
type Row = Task & { children?: Row[] }

export interface TaskDrawerState {
  open: boolean
  taskId?: string
  newType?: TaskType
  parentId?: string
}

/** Задачі проєкту деревом (батьківська → підзадачі). Нові задачі — менеджер і супервайзер проєкту. */
export function ProjectTasksTab({ project }: { project: Project }) {
  const { t, i18n } = useTranslation()
  const lang = i18n.language
  const tasks = useProjectTasks(project.id)
  const types = useTaskTypes(true)
  const rights = useTaskRights(project)
  const [filter, setFilter] = useState<Filter>('open')
  const [onlyMine, setOnlyMine] = useState(false)
  const [drawer, setDrawer] = useState<TaskDrawerState>({ open: false })

  const typeList = useMemo(() => types.data ?? [], [types.data])
  const typeById = useMemo(() => new Map(typeList.map((tt) => [tt.id, tt])), [typeList])
  const activeTypes = typeList.filter((tt) => !tt.archived)

  const rows = useMemo(() => {
    const matches = (task: Task) =>
      (filter === 'all' ||
        (filter === 'open' ? task.statusCategory === 'new' || task.statusCategory === 'active' : task.statusCategory === filter)) &&
      (!onlyMine || task.assigneeId === rights.userId)
    const all = tasks.data ?? []
    const byParent = new Map<string, Task[]>()
    for (const task of all) {
      if (task.parentId) byParent.set(task.parentId, [...(byParent.get(task.parentId) ?? []), task])
    }
    // Дерево: задача показується, якщо підходить сама або хтось із нащадків
    const build = (task: Task): Row | null => {
      const children = (byParent.get(task.id) ?? []).map(build).filter((r): r is Row => r !== null)
      if (!matches(task) && children.length === 0) return null
      return children.length > 0 ? { ...task, children } : { ...task }
    }
    const ids = new Set(all.map((task) => task.id))
    return all
      .filter((task) => !task.parentId || !ids.has(task.parentId))
      .map(build)
      .filter((r): r is Row => r !== null)
  }, [tasks.data, filter, onlyMine, rights.userId])

  const statusTag = (task: Task) => {
    const status = typeById.get(task.typeId)?.statuses.find((s) => s.code === task.status)
    const color = status?.color && status.color !== 'default' ? status.color : CATEGORY_COLORS[task.statusCategory]
    return <Tag color={color}>{localized(status?.label, lang) || task.status}</Tag>
  }

  const newButton =
    activeTypes.length === 1 ? (
      <Button type="primary" icon={<PlusOutlined />} onClick={() => setDrawer({ open: true, newType: activeTypes[0] })}>
        {t('tasks.add')}
      </Button>
    ) : (
      <Dropdown
        disabled={activeTypes.length === 0}
        menu={{
          items: activeTypes.map((tt) => ({ key: tt.id, label: localized(tt.name, lang) })),
          onClick: ({ key }) => setDrawer({ open: true, newType: typeById.get(key) }),
        }}
      >
        <Button type="primary" icon={<PlusOutlined />}>
          {t('tasks.add')} <DownOutlined />
        </Button>
      </Dropdown>
    )

  return (
    <>
      <Flex justify="space-between" gap={8} wrap style={{ marginBottom: 16 }}>
        <Flex gap={8} wrap>
          <Segmented<Filter>
            value={filter}
            onChange={setFilter}
            options={[
              { value: 'open', label: t('tasks.filter.open') },
              { value: 'done', label: t('tasks.category.done') },
              { value: 'cancelled', label: t('tasks.category.cancelled') },
              { value: 'all', label: t('projects.all') },
            ]}
          />
          <Select
            value={onlyMine ? 'mine' : 'all'}
            onChange={(v) => setOnlyMine(v === 'mine')}
            style={{ width: 160 }}
            options={[
              { value: 'all', label: t('tasks.everyone') },
              { value: 'mine', label: t('tasks.mineOnly') },
            ]}
          />
        </Flex>
        {rights.full && newButton}
      </Flex>

      <Table<Row>
        rowKey="id"
        size="middle"
        loading={tasks.isPending || types.isPending}
        dataSource={rows}
        pagination={{ pageSize: 100, hideOnSinglePage: true }}
        expandable={{ defaultExpandAllRows: true }}
        scroll={{ x: 'max-content' }}
        locale={{ emptyText: t('tasks.empty') }}
        columns={[
          {
            title: t('tasks.title'),
            key: 'title',
            render: (_, task) => (
              <Flex gap={6} align="center">
                <Typography.Text type="secondary">#{task.number}</Typography.Text>
                <Typography.Link onClick={() => setDrawer({ open: true, taskId: task.id })}>{task.title}</Typography.Link>
                {task.openBlockers > 0 && <LockOutlined title={t('tasks.blockedCount', { count: task.openBlockers })} />}
              </Flex>
            ),
          },
          { title: t('assignments.type'), key: 'type', render: (_, task) => localized(typeById.get(task.typeId)?.name, lang) },
          { title: t('projects.statusLabel'), key: 'status', render: (_, task) => statusTag(task) },
          { title: t('tasks.assignee'), dataIndex: 'assigneeName' },
          {
            title: t('tasks.subject'),
            key: 'subject',
            render: (_, task) => task.serviceCode ?? task.assetCode ?? '',
          },
          {
            title: t('services.plannedEnd'),
            key: 'plannedEnd',
            render: (_, task) => {
              if (!task.plannedEnd) return ''
              const overdue =
                (task.statusCategory === 'new' || task.statusCategory === 'active') && dayjs(task.plannedEnd).isBefore(dayjs(), 'day')
              return <Typography.Text type={overdue ? 'danger' : undefined}>{dayjs(task.plannedEnd).format('DD.MM.YYYY')}</Typography.Text>
            },
          },
          {
            title: t('tasks.children'),
            key: 'children',
            render: (_, task) => (task.childrenTotal > 0 ? `${task.childrenTotal - task.childrenOpen}/${task.childrenTotal}` : ''),
          },
        ]}
      />

      <TaskDrawer
        open={drawer.open}
        project={project}
        types={typeList}
        taskId={drawer.taskId}
        newType={drawer.newType}
        parentId={drawer.parentId}
        onClose={() => setDrawer({ open: false })}
        onOpenTask={(id) => setDrawer({ open: true, taskId: id })}
        onAddChild={(parent, childType) => setDrawer({ open: true, parentId: parent.id, newType: childType })}
      />
    </>
  )
}
