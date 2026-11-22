import { PlusOutlined } from '@ant-design/icons'
import { App, Button, Empty, Flex, Form, Input, Modal, Select, Switch, Table, Tag } from 'antd'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useInstallTemplate } from '../../api/assets'
import { hasPermission, useSession } from '../../api/auth'
import { applyFieldErrors, errorMessage } from '../../api/formErrors'
import { type Position, useArchivePosition, usePositions, useSavePosition } from '../../api/personnel'
import { localized } from '../../i18n/localized'

interface FormValues {
  code: string
  name: string
  projectRole: Position['projectRole']
  supervisorCanAdd: boolean
}

/** Довідник посад. Назва редагується поточною мовою, інші мови зберігаються. */
export function PositionsTab() {
  const { t, i18n } = useTranslation()
  const { message } = App.useApp()
  const { data: session } = useSession()
  const positions = usePositions(true)
  const save = useSavePosition()
  const archive = useArchivePosition()
  const installTemplate = useInstallTemplate()
  const [form] = Form.useForm<FormValues>()
  const [editing, setEditing] = useState<{ open: boolean; position?: Position }>({ open: false })
  const canManage = hasPermission(session, 'catalog.manage')

  const open = (position?: Position) => {
    form.resetFields()
    form.setFieldsValue(
      position
        ? { ...position, name: localized(position.name, i18n.language) }
        : { projectRole: 'none', supervisorCanAdd: false },
    )
    setEditing({ open: true, position })
  }

  const onFinish = (values: FormValues) => {
    const name = { ...(editing.position?.name ?? {}), [i18n.language]: values.name }
    save.mutate(
      { id: editing.position?.id, body: { ...values, name } },
      {
        onSuccess: () => setEditing({ open: false }),
        onError: (error) => {
          if (!applyFieldErrors(form, error, t)) message.error(errorMessage(error, t))
        },
      },
    )
  }

  if (positions.isSuccess && positions.data.length === 0) {
    return (
      <Empty description={t('personnel.noPositions')}>
        {canManage && (
          <Button type="primary" loading={installTemplate.isPending} onClick={() => installTemplate.mutate('exploration-drilling')}>
            {t('assets.installTemplate')}
          </Button>
        )}
      </Empty>
    )
  }

  return (
    <>
      {canManage && (
        <Flex justify="flex-end" style={{ marginBottom: 16 }}>
          <Button icon={<PlusOutlined />} onClick={() => open()}>
            {t('personnel.addPosition')}
          </Button>
        </Flex>
      )}
      <Table<Position>
        rowKey="id"
        size="middle"
        pagination={false}
        loading={positions.isPending}
        dataSource={positions.data ?? []}
        columns={[
          {
            title: t('projects.name'),
            key: 'name',
            render: (_, p) => (
              <Flex gap={8}>
                {canManage ? <a onClick={() => open(p)}>{localized(p.name, i18n.language)}</a> : localized(p.name, i18n.language)}
                {p.archived && <Tag>{t('validation.archived')}</Tag>}
              </Flex>
            ),
          },
          { title: t('projects.code'), dataIndex: 'code' },
          {
            title: t('personnel.projectRole'),
            dataIndex: 'projectRole',
            render: (role: string) => (role === 'none' ? '—' : <Tag color="orange">{t(`personnel.projectRoles.${role}`)}</Tag>),
          },
          {
            title: t('personnel.supervisorCanAdd'),
            dataIndex: 'supervisorCanAdd',
            render: (value: boolean) => (value ? '✓' : ''),
          },
          ...(canManage
            ? [
                {
                  key: 'actions',
                  align: 'right' as const,
                  render: (_: unknown, p: Position) => (
                    <Button type="link" size="small" onClick={() => archive.mutate({ id: p.id, restore: p.archived })}>
                      {p.archived ? t('customers.restore') : t('assets.archive')}
                    </Button>
                  ),
                },
              ]
            : []),
        ]}
      />
      <Modal
        open={editing.open}
        title={editing.position ? localized(editing.position.name, i18n.language) : t('personnel.addPosition')}
        onCancel={() => setEditing({ open: false })}
        onOk={() => form.submit()}
        okText={t('common.save')}
        cancelText={t('common.cancel')}
        confirmLoading={save.isPending}
        destroyOnHidden
      >
        <Form<FormValues> form={form} layout="vertical" onFinish={onFinish}>
          <Form.Item name="name" label={t('projects.name')} rules={[{ required: true, message: t('validation.required') }]}>
            <Input />
          </Form.Item>
          <Form.Item
            name="code"
            label={t('projects.code')}
            extra={t('personnel.codeHint')}
            rules={[{ required: true, message: t('validation.required') }]}
          >
            <Input disabled={!!editing.position} />
          </Form.Item>
          <Form.Item name="projectRole" label={t('personnel.projectRole')} extra={t('personnel.projectRoleHint')}>
            <Select
              options={(['none', 'supervisor', 'storekeeper'] as const).map((r) => ({
                value: r,
                label: r === 'none' ? '—' : t(`personnel.projectRoles.${r}`),
              }))}
            />
          </Form.Item>
          <Form.Item name="supervisorCanAdd" label={t('personnel.supervisorCanAdd')} valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
      </Modal>
    </>
  )
}
