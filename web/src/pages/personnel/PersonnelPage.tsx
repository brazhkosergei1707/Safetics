import { KeyOutlined, PlusOutlined } from '@ant-design/icons'
import { Button, Flex, Input, Switch, Table, Tabs, Tag, Typography } from 'antd'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { hasPermission, useSession } from '../../api/auth'
import { type Personnel, usePersonnel, usePositions } from '../../api/personnel'
import { localized } from '../../i18n/localized'
import { PersonnelDrawer } from './PersonnelDrawer'
import { PositionsTab } from './PositionsTab'

/** Виконавці компанії і довідник посад. */
export function PersonnelPage() {
  const { t, i18n } = useTranslation()
  const { data: session } = useSession()
  const [query, setQuery] = useState('')
  const [includeArchived, setIncludeArchived] = useState(false)
  const [editing, setEditing] = useState<{ open: boolean; person?: Personnel }>({ open: false })
  const canManage = hasPermission(session, 'personnel.manage')
  const personnel = usePersonnel({ q: query, includeArchived }, canManage)
  const positions = usePositions(true)
  const positionName = (id?: string | null) =>
    localized(positions.data?.find((p) => p.id === id)?.name, i18n.language)

  const people = (
    <>
      <Flex justify="space-between" gap={16} align="center" wrap style={{ marginBottom: 16 }}>
        <Flex gap={16} align="center" wrap>
          <Input.Search allowClear placeholder={t('personnel.search')} onSearch={setQuery} style={{ maxWidth: 320 }} />
          <Flex gap={8} align="center">
            <Switch size="small" checked={includeArchived} onChange={setIncludeArchived} />
            {t('customers.showArchived')}
          </Flex>
        </Flex>
        {canManage && (
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setEditing({ open: true })}>
            {t('personnel.add')}
          </Button>
        )}
      </Flex>
      <Table<Personnel>
        rowKey="id"
        size="middle"
        loading={personnel.isPending}
        dataSource={personnel.data ?? []}
        pagination={{ pageSize: 50, hideOnSinglePage: true }}
        columns={[
          {
            title: t('customers.fullName'),
            dataIndex: 'fullName',
            render: (name: string, person) => (
              <Flex gap={8} align="center">
                <Typography.Link onClick={() => setEditing({ open: true, person })}>{name}</Typography.Link>
                {person.loginRole && (
                  <Tag icon={<KeyOutlined />} color="blue">
                    {t(`roles.${person.loginRole}`, { defaultValue: person.loginRole })}
                  </Tag>
                )}
                {person.archived && <Tag>{t('validation.archived')}</Tag>}
              </Flex>
            ),
          },
          { title: t('personnel.position'), key: 'position', render: (_, p) => positionName(p.positionId) },
          {
            title: t('personnel.employmentType'),
            dataIndex: 'employmentType',
            render: (value: string, p) =>
              [t(`personnel.employment.${value}`), p.organization].filter(Boolean).join(' · '),
          },
          { title: t('customers.phone'), dataIndex: 'phone' },
        ]}
      />
    </>
  )

  return (
    <>
      <Typography.Title level={2}>{t('nav.personnel')}</Typography.Title>
      <Tabs
        items={[
          ...(canManage ? [{ key: 'people', label: t('personnel.tabs.people'), children: people }] : []),
          { key: 'positions', label: t('personnel.tabs.positions'), children: <PositionsTab /> },
        ]}
      />
      <PersonnelDrawer open={editing.open} person={editing.person} onClose={() => setEditing({ open: false })} />
    </>
  )
}
