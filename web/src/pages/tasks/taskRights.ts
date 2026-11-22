import { hasPermission, useSession } from '../../api/auth'
import { type Project, useMembers, useProjectAccess, useProjectPersonnel } from '../../api/projects'
import type { Task } from '../../api/tasks'

/**
 * Хто що може із задачами проєкту: менеджер і супервайзер — усе; виконавець — статус, дати й форма
 * закриття своїх. Сервер перевіряє те саме; тут — лише щоб не показувати недоступні кнопки.
 */
export function useTaskRights(project: Project) {
  const { data: session } = useSession()
  const crew = useProjectPersonnel(project.id)
  const manage = hasPermission(session, 'project.manage')
  const supervisor = (crew.data ?? []).some(
    (a) => a.active && a.projectRole === 'supervisor' && a.userId === session?.user.id,
  )
  const open = project.status === 'active'
  const full = open && (manage || supervisor)
  return {
    full,
    manage,
    userId: session?.user.id,
    isAssignee: (task?: Task) => !!task && !!session && task.assigneeId === session.user.id,
    canWork: (task?: Task) => open && (full || (!!task && !!session && task.assigneeId === session.user.id)),
  }
}

/** Кому можна призначити задачу: люди з входом, які бачать проєкт (бригада, доступ, «усі проєкти»). */
export function useAssigneeOptions(project: Project) {
  const { data: session } = useSession()
  const manage = hasPermission(session, 'project.manage')
  const crew = useProjectPersonnel(project.id)
  const access = useProjectAccess(project.id, manage)
  const members = useMembers(manage)
  const options = new Map<string, string>()
  for (const a of crew.data ?? []) {
    if (a.active && a.userId) options.set(a.userId, a.fullName)
  }
  for (const a of access.data ?? []) options.set(a.userId, a.fullName)
  for (const m of members.data ?? []) {
    if (m.status === 'active' && m.allProjects && !m.portal) options.set(m.userId, m.fullName)
  }
  if (session) options.set(session.user.id, session.user.fullName)
  return [...options.entries()].map(([value, label]) => ({ value, label })).sort((a, b) => a.label.localeCompare(b.label))
}
