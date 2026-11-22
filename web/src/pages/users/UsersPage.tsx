import { PlusOutlined } from '@ant-design/icons'
import { App, Button, Flex, Form, Input, Modal, Popconfirm, Select, Table, Tag, Typography } from 'antd'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useSession } from '../../api/auth'
import { applyFieldErrors, errorMessage } from '../../api/formErrors'
import { STAFF_ROLES } from '../../api/personnel'
import { type CompanyUser, useAddUser, useChangeRole, useSetUserEnabled, useUsers } from '../../api/users'

interface AddValues {
  phone: string
  fullName: string
  role: string
  password?: string
}

/**
 * Користувачі компанії: хто може входити і з якою роллю. Роль не може бути ширшою за вашу.
 * Представники замовника керуються в картці замовника.
 */
export function UsersPage() {
  const { t } = useTranslation()
  const { message } = App.useApp()
  const { data: session } = useSession()
  const users = useUsers()
  const add = useAddUser()
  const changeRole = useChangeRole()
  const setEnabled = useSetUserEnabled()
  const [form] = Form.useForm<AddValues>()
  const [open, setOpen] = useState(false)

  const roleOptions = STAFF_ROLES.map((r) => ({ value: r, label: t(`roles.${r}`) }))
  const onError = (error: Error) => message.error(errorMessage(error, t))

  const onFinish = (values: AddValues) => {
    add.mutate(values, {
      onSuccess: () => {
        message.success(t('common.saved'))
        setOpen(false)
      },
      onError: (error) => {
        if (!applyFieldErrors(form, error, t)) message.error(errorMessage(error, t))
      },
    })
  }

  return (
    <>
      <Flex justify="space-between" align="center" style={{ marginBottom: 8 }}>
        <Typography.Title level={2} style={{ margin: 0 }}>
          {t('nav.users')}
        </Typography.Title>
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => {
            form.resetFields()
            form.setFieldsValue({ role: 'manager' })
            setOpen(true)
          }}
        >
          {t('users.add')}
        </Button>
      </Flex>
      <Typography.Paragraph type="secondary">{t('users.hint')}</Typography.Paragraph>

      <Table<CompanyUser>
        rowKey="userId"
        size="middle"
        loading={users.isPending}
        dataSource={users.data ?? []}
        pagination={{ pageSize: 50, hideOnSinglePage: true }}
        columns={[
          {
            title: t('customers.fullName'),
            dataIndex: 'fullName',
            render: (name: string, u) => (
              <Flex gap={8} align="center">
                {name}
                {u.userId === session?.user.id && <Tag>{t('users.you')}</Tag>}
                {u.status === 'disabled' && <Tag color="red">{t('users.disabled')}</Tag>}
              </Flex>
            ),
          },
          { title: t('customers.phone'), dataIndex: 'phone' },
          {
            title: t('session.role'),
            key: 'role',
            render: (_, u) =>
              u.portal || u.userId === session?.user.id || u.status === 'disabled' ? (
                t(`roles.${u.role}`, { defaultValue: u.role })
              ) : (
                <Select
                  size="small"
                  value={u.role}
                  style={{ minWidth: 170 }}
                  options={roleOptions}
                  loading={changeRole.isPending && changeRole.variables?.userId === u.userId}
                  onChange={(role) => changeRole.mutate({ userId: u.userId, role }, { onError })}
                />
              ),
          },
          {
            key: 'actions',
            align: 'right',
            render: (_, u) =>
              u.portal ? (
                <Typography.Text type="secondary">{t('users.portalHint')}</Typography.Text>
              ) : u.userId === session?.user.id ? null : (
                <Popconfirm
                  title={u.status === 'disabled' ? t('users.enableConfirm') : t('users.disableConfirm')}
                  onConfirm={() => setEnabled.mutate({ userId: u.userId, enabled: u.status === 'disabled' }, { onError })}
                >
                  <Button type="link" size="small" danger={u.status !== 'disabled'}>
                    {u.status === 'disabled' ? t('users.enable') : t('users.disable')}
                  </Button>
                </Popconfirm>
              ),
          },
        ]}
      />

      <Modal
        open={open}
        title={t('users.add')}
        onCancel={() => setOpen(false)}
        onOk={() => form.submit()}
        okText={t('common.save')}
        cancelText={t('common.cancel')}
        confirmLoading={add.isPending}
        destroyOnHidden
      >
        <Form<AddValues> form={form} layout="vertical" onFinish={onFinish} requiredMark="optional">
          <Form.Item name="fullName" label={t('customers.fullName')} rules={[{ required: true, message: t('validation.required') }]}>
            <Input />
          </Form.Item>
          <Form.Item
            name="phone"
            label={t('customers.phone')}
            extra={t('personnel.phoneHint')}
            rules={[{ required: true, message: t('validation.required') }]}
          >
            <Input />
          </Form.Item>
          <Form.Item name="role" label={t('session.role')} rules={[{ required: true }]}>
            <Select options={roleOptions} />
          </Form.Item>
          <Form.Item name="password" label={t('customers.initialPassword')} extra={t('customers.initialPasswordHint')}>
            <Input.Password autoComplete="new-password" />
          </Form.Item>
        </Form>
      </Modal>
    </>
  )
}
