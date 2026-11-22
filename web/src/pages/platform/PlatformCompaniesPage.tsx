import { PlusOutlined } from '@ant-design/icons'
import { App, Button, Drawer, Flex, Form, Input, Popconfirm, Select, Table, Tag, Typography } from 'antd'
import dayjs from 'dayjs'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { applyFieldErrors, errorMessage } from '../../api/formErrors'
import {
  type CreateCompanyRequest,
  type PlatformCompany,
  useCreateCompany,
  usePlatformCompanies,
  useSetCompanyStatus,
  useTemplates,
} from '../../api/platform'
import { localized } from '../../i18n/localized'

/** Компанії платформи: створення з першим адміном і галузевим шаблоном, призупинення. */
export function PlatformCompaniesPage() {
  const { t, i18n } = useTranslation()
  const { message, modal } = App.useApp()
  const companies = usePlatformCompanies()
  const templates = useTemplates()
  const create = useCreateCompany()
  const setStatus = useSetCompanyStatus()
  const [form] = Form.useForm<CreateCompanyRequest>()
  const [open, setOpen] = useState(false)

  const templateName = (key?: string | null) =>
    localized(templates.data?.find((tpl) => tpl.key === key)?.name, i18n.language) || key || '—'

  const onFinish = (values: CreateCompanyRequest) => {
    create.mutate(values, {
      onSuccess: (result) => {
        setOpen(false)
        modal.success({
          title: t('platform.created', { name: result.company.name }),
          content: result.newAdminAccount
            ? t('platform.newAdminAccount', { phone: values.adminPhone })
            : t('platform.existingAdminAccount', { phone: values.adminPhone }),
        })
      },
      onError: (error) => {
        // Пароль нового адміна сервер повертає як поле password
        if (!applyFieldErrors(form, error, t, { password: 'adminPassword', phone: 'adminPhone' })) {
          message.error(errorMessage(error, t))
        }
      },
    })
  }

  return (
    <>
      <Flex justify="space-between" align="center" style={{ marginBottom: 16 }}>
        <Typography.Title level={2} style={{ margin: 0 }}>
          {t('nav.platform')}
        </Typography.Title>
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => {
            form.resetFields()
            setOpen(true)
          }}
        >
          {t('platform.add')}
        </Button>
      </Flex>

      <Table<PlatformCompany>
        rowKey="id"
        size="middle"
        loading={companies.isPending}
        dataSource={companies.data ?? []}
        pagination={{ pageSize: 50, hideOnSinglePage: true }}
        columns={[
          {
            title: t('customers.name'),
            dataIndex: 'name',
            render: (name: string, c) => (
              <Flex gap={8} align="center">
                {name}
                {c.status !== 'active' && <Tag color="red">{t(`platform.status.${c.status}`)}</Tag>}
              </Flex>
            ),
          },
          { title: t('platform.template'), dataIndex: 'templateKey', render: (key: string) => templateName(key) },
          {
            title: t('platform.admins'),
            key: 'admins',
            render: (_, c) => c.admins.map((a) => `${a.fullName} (${a.phone})`).join(', ') || '—',
          },
          { title: t('platform.members'), dataIndex: 'members', align: 'right' },
          { title: t('platform.createdAt'), dataIndex: 'createdAt', render: (v: string) => dayjs(v).format('DD.MM.YYYY') },
          {
            key: 'actions',
            align: 'right',
            render: (_, c) => (
              <Popconfirm
                title={c.status === 'active' ? t('platform.suspendConfirm') : t('platform.activateConfirm')}
                onConfirm={() =>
                  setStatus.mutate(
                    { id: c.id, suspend: c.status === 'active' },
                    { onError: (e) => message.error(errorMessage(e, t)) },
                  )
                }
              >
                <Button type="link" size="small" danger={c.status === 'active'}>
                  {c.status === 'active' ? t('platform.suspend') : t('platform.activate')}
                </Button>
              </Popconfirm>
            ),
          },
        ]}
      />

      <Drawer
        open={open}
        onClose={() => setOpen(false)}
        size={480}
        destroyOnHidden
        title={t('platform.add')}
        footer={
          <Flex justify="flex-end" gap={8}>
            <Button onClick={() => setOpen(false)}>{t('common.cancel')}</Button>
            <Button type="primary" loading={create.isPending} onClick={() => form.submit()}>
              {t('common.save')}
            </Button>
          </Flex>
        }
      >
        <Form<CreateCompanyRequest> form={form} layout="vertical" onFinish={onFinish} requiredMark="optional">
          <Form.Item name="name" label={t('customers.name')} rules={[{ required: true, message: t('validation.required') }]}>
            <Input />
          </Form.Item>
          <Form.Item name="legalCode" label={t('customers.legalCode')}>
            <Input />
          </Form.Item>
          <Form.Item
            name="templateKey"
            label={t('platform.template')}
            extra={t('platform.templateHint')}
            rules={[{ required: true, message: t('validation.required') }]}
          >
            <Select
              loading={templates.isPending}
              options={(templates.data ?? []).map((tpl) => ({ value: tpl.key, label: localized(tpl.name, i18n.language) }))}
            />
          </Form.Item>
          <Typography.Title level={5}>{t('platform.firstAdmin')}</Typography.Title>
          <Form.Item name="adminFullName" label={t('customers.fullName')} rules={[{ required: true, message: t('validation.required') }]}>
            <Input />
          </Form.Item>
          <Form.Item
            name="adminPhone"
            label={t('customers.phone')}
            extra={t('personnel.phoneHint')}
            rules={[{ required: true, message: t('validation.required') }]}
          >
            <Input placeholder="+380 67 000 00 00" />
          </Form.Item>
          <Form.Item name="adminPassword" label={t('customers.initialPassword')} extra={t('customers.initialPasswordHint')}>
            <Input.Password autoComplete="new-password" />
          </Form.Item>
        </Form>
      </Drawer>
    </>
  )
}
