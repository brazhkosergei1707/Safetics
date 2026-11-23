import { PlusOutlined } from '@ant-design/icons'
import { Button, Flex, Input, Switch, Table, Tag, Typography } from 'antd'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { hasPermission, useSession } from '../../api/auth'
import { type Customer, useCustomers } from '../../api/customers'
import { CustomerDrawer } from './CustomerDrawer'

export function CustomersPage() {
  const { t } = useTranslation()
  const { data: session } = useSession()
  const [query, setQuery] = useState('')
  const [includeArchived, setIncludeArchived] = useState(false)
  const [editing, setEditing] = useState<{ open: boolean; customer?: Customer }>({ open: false })
  const customers = useCustomers({ q: query, includeArchived })
  const canEdit = hasPermission(session, 'client.edit')
  // Створювати й архівувати замовників — головний менеджер і вище (client.view_all)
  const canCreate = canEdit && hasPermission(session, 'client.view_all')

  return (
    <>
      <Flex justify="space-between" align="center" style={{ marginBottom: 16 }}>
        <Typography.Title level={2} style={{ margin: 0 }}>
          {t('nav.customers')}
        </Typography.Title>
        {canCreate && (
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setEditing({ open: true })}>
            {t('customers.add')}
          </Button>
        )}
      </Flex>

      <Flex gap={16} align="center" wrap style={{ marginBottom: 16 }}>
        <Input.Search allowClear placeholder={t('customers.search')} onSearch={setQuery} style={{ maxWidth: 320 }} />
        <Flex gap={8} align="center">
          <Switch size="small" checked={includeArchived} onChange={setIncludeArchived} />
          {t('customers.showArchived')}
        </Flex>
      </Flex>

      <Table<Customer>
        rowKey="id"
        size="middle"
        loading={customers.isPending}
        dataSource={customers.data ?? []}
        pagination={{ pageSize: 50, hideOnSinglePage: true }}
        columns={[
          {
            title: t('customers.name'),
            dataIndex: 'name',
            render: (name: string, customer) => (
              <Flex gap={8} align="center">
                <Typography.Link onClick={() => setEditing({ open: true, customer })}>{name}</Typography.Link>
                {customer.archived && <Tag>{t('validation.archived')}</Tag>}
              </Flex>
            ),
          },
          { title: t('customers.legalCode'), dataIndex: 'legalCode' },
          { title: t('customers.country'), dataIndex: 'country' },
          { title: t('customers.email'), dataIndex: 'email' },
          { title: t('customers.phone'), dataIndex: 'phone' },
        ]}
      />

      <CustomerDrawer
        open={editing.open}
        customer={editing.customer}
        canEdit={canEdit}
        canArchive={canCreate}
        onClose={() => setEditing({ open: false })}
        onCreated={(customer) => setEditing({ open: true, customer })}
      />
    </>
  )
}
