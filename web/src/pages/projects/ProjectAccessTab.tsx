import { App, Alert, Button, Flex, Popconfirm, Select, Table } from 'antd'
import dayjs from 'dayjs'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { errorMessage } from '../../api/formErrors'
import {
  type Project,
  type ProjectAccess,
  useGrantProjectAccess,
  useMembers,
  useProjectAccess,
  useRevokeProjectAccess,
} from '../../api/projects'

/**
 * Хто бачить проєкт. Адмін і керівник бачать усі проєкти без окремого доступу; представники
 * замовника — проєкти свого замовника. Тут — доступ для решти (менеджери, супервайзери, виконавці).
 */
export function ProjectAccessTab({ project }: { project: Project }) {
  const { t } = useTranslation()
  const { message } = App.useApp()
  const access = useProjectAccess(project.id, true)
  const members = useMembers(true)
  const grant = useGrantProjectAccess(project.id)
  const revoke = useRevokeProjectAccess(project.id)
  const [userId, setUserId] = useState<string>()

  const withAccess = new Set((access.data ?? []).map((a) => a.userId))
  const candidates = (members.data ?? []).filter(
    (m) => m.status === 'active' && !m.portal && !m.allProjects && !withAccess.has(m.userId),
  )
  const allProjects = (members.data ?? []).filter((m) => m.status === 'active' && m.allProjects)

  return (
    <>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        title={t('projects.accessHint')}
        description={
          allProjects.length > 0
            ? t('projects.allProjectsMembers', { names: allProjects.map((m) => m.fullName).join(', ') })
            : undefined
        }
      />
      <Flex gap={8} style={{ marginBottom: 16 }}>
        <Select
          showSearch={{ optionFilterProp: 'label' }}
          placeholder={t('projects.chooseMember')}
          value={userId}
          onChange={setUserId}
          style={{ minWidth: 320 }}
          loading={members.isPending}
          options={candidates.map((m) => ({
            value: m.userId,
            label: `${m.fullName} · ${t(`roles.${m.role}`, { defaultValue: m.role })}`,
          }))}
        />
        <Button
          type="primary"
          disabled={!userId}
          loading={grant.isPending}
          onClick={() =>
            userId &&
            grant.mutate(userId, {
              onSuccess: () => setUserId(undefined),
              onError: (error) => message.error(errorMessage(error, t)),
            })
          }
        >
          {t('projects.grantAccess')}
        </Button>
      </Flex>
      <Table<ProjectAccess>
        rowKey="userId"
        size="middle"
        pagination={false}
        loading={access.isPending}
        dataSource={access.data ?? []}
        columns={[
          { title: t('projects.member'), dataIndex: 'fullName' },
          { title: t('login.phone'), dataIndex: 'phone' },
          {
            title: t('session.role'),
            dataIndex: 'role',
            render: (role: string) => t(`roles.${role}`, { defaultValue: role }),
          },
          {
            title: t('projects.grantedBy'),
            key: 'granted',
            render: (_, a) => [a.grantedByName, dayjs(a.grantedAt).format('DD.MM.YYYY')].filter(Boolean).join(' · '),
          },
          {
            key: 'actions',
            align: 'right',
            render: (_, a) => (
              <Popconfirm title={t('projects.revokeAccessConfirm')} onConfirm={() => revoke.mutate(a.userId)}>
                <Button type="link" size="small" danger>
                  {t('projects.revokeAccess')}
                </Button>
              </Popconfirm>
            ),
          },
        ]}
      />
    </>
  )
}
