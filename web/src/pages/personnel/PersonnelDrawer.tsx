import { KeyOutlined } from '@ant-design/icons'
import { App, Button, Divider, Drawer, Flex, Form, Input, List, Modal, Popconfirm, Select, Tag, Typography } from 'antd'
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { ApiError } from '../../api/client'
import { applyFieldErrors, errorMessage } from '../../api/formErrors'
import {
  EMPLOYMENT_TYPES,
  type Personnel,
  type PersonnelRequest,
  useArchivePersonnel,
  useGrantLogin,
  usePersonnelHistory,
  usePositions,
  useRevokeLogin,
  useSavePersonnel,
} from '../../api/personnel'
import { formatPeriod, useProjects } from '../../api/projects'
import { localized } from '../../i18n/localized'

interface Props {
  open: boolean
  /** undefined — новий виконавець */
  person?: Personnel
  onClose: () => void
}

export function PersonnelDrawer({ open, person, onClose }: Props) {
  const { t, i18n } = useTranslation()
  const { message } = App.useApp()
  const [form] = Form.useForm<PersonnelRequest>()
  const save = useSavePersonnel()
  const archive = useArchivePersonnel()
  const revoke = useRevokeLogin()
  const positions = usePositions()
  const history = usePersonnelHistory(open ? person?.id : undefined)
  const projects = useProjects({})
  const [loginOpen, setLoginOpen] = useState(false)
  const employmentType = Form.useWatch('employmentType', form)

  useEffect(() => {
    if (!open) return
    form.resetFields()
    form.setFieldsValue(person ?? { employmentType: 'staff' })
  }, [open, person, form])

  const onFinish = (values: PersonnelRequest) => {
    save.mutate(
      { id: person?.id, body: { ...values, version: person?.version } },
      {
        onSuccess: () => {
          message.success(t('common.saved'))
          onClose()
        },
        onError: (error) => {
          if (error instanceof ApiError && error.code === 'stale_version') message.error(t('apiErrors.stale_version'))
          else if (!applyFieldErrors(form, error, t)) message.error(errorMessage(error, t))
        },
      },
    )
  }

  const projectCode = (id: string) => projects.data?.find((p) => p.id === id)?.code ?? '—'

  return (
    <Drawer
      open={open}
      onClose={onClose}
      size={520}
      destroyOnHidden
      title={person ? person.fullName : t('personnel.new')}
      footer={
        <Flex justify="space-between" gap={8}>
          {person ? (
            <Popconfirm
              title={person.archived ? t('customers.restoreConfirm') : t('personnel.archiveConfirm')}
              onConfirm={() =>
                archive.mutate(
                  { id: person.id, restore: person.archived },
                  { onSuccess: onClose, onError: (e) => message.error(errorMessage(e, t)) },
                )
              }
            >
              <Button danger={!person.archived}>{person.archived ? t('customers.restore') : t('assets.archive')}</Button>
            </Popconfirm>
          ) : (
            <span />
          )}
          <Flex gap={8}>
            <Button onClick={onClose}>{t('common.cancel')}</Button>
            <Button type="primary" loading={save.isPending} onClick={() => form.submit()}>
              {t('common.save')}
            </Button>
          </Flex>
        </Flex>
      }
    >
      <Form<PersonnelRequest> form={form} layout="vertical" onFinish={onFinish} requiredMark="optional">
        <Form.Item name="fullName" label={t('customers.fullName')} rules={[{ required: true, message: t('validation.required') }]}>
          <Input />
        </Form.Item>
        <Form.Item name="positionId" label={t('personnel.position')}>
          <Select
            allowClear
            options={(positions.data ?? []).map((p) => ({ value: p.id, label: localized(p.name, i18n.language) }))}
          />
        </Form.Item>
        <Form.Item name="employmentType" label={t('personnel.employmentType')}>
          <Select options={EMPLOYMENT_TYPES.map((e) => ({ value: e, label: t(`personnel.employment.${e}`) }))} />
        </Form.Item>
        {employmentType && employmentType !== 'staff' && (
          <Form.Item name="organization" label={t('personnel.organization')}>
            <Input />
          </Form.Item>
        )}
        <Form.Item name="phone" label={t('customers.phone')} extra={t('personnel.phoneHint')}>
          <Input />
        </Form.Item>
        <Form.Item name="notes" label={t('customers.notes')}>
          <Input.TextArea rows={2} />
        </Form.Item>
      </Form>

      {person && !person.archived && (
        <>
          <Divider titlePlacement="start">{t('personnel.login')}</Divider>
          {person.loginRole ? (
            <Flex gap={8} align="center" justify="space-between">
              <Tag icon={<KeyOutlined />} color="blue">
                {t(`roles.${person.loginRole}`, { defaultValue: person.loginRole })}
              </Tag>
              <Popconfirm
                title={t('personnel.revokeLoginConfirm')}
                onConfirm={() => revoke.mutate(person.id, { onError: (e) => message.error(errorMessage(e, t)) })}
              >
                <Button size="small" danger>
                  {t('customers.revokePortal')}
                </Button>
              </Popconfirm>
            </Flex>
          ) : (
            <Flex vertical gap={8}>
              <Typography.Text type="secondary">{t('personnel.noLogin')}</Typography.Text>
              <Button icon={<KeyOutlined />} onClick={() => setLoginOpen(true)} style={{ alignSelf: 'flex-start' }}>
                {t('personnel.grantLogin')}
              </Button>
            </Flex>
          )}

          <Divider titlePlacement="start">{t('personnel.assignments')}</Divider>
          <List
            size="small"
            loading={history.isPending}
            dataSource={history.data ?? []}
            locale={{ emptyText: t('personnel.noAssignments') }}
            renderItem={(a) => (
              <List.Item>
                <Flex gap={8} align="center" wrap>
                  <Link to={`/projects/${a.projectId}`}>{projectCode(a.projectId)}</Link>
                  {localized(a.positionName, i18n.language)}
                  <Typography.Text type="secondary">{formatPeriod(a.startDate, a.endDate)}</Typography.Text>
                  {a.active && <Tag color="green">{t('projects.current')}</Tag>}
                </Flex>
              </List.Item>
            )}
          />
          <LoginModal person={loginOpen ? person : undefined} onClose={() => setLoginOpen(false)} />
        </>
      )}
    </Drawer>
  )
}

interface LoginValues {
  password?: string
}

function LoginModal({ person, onClose }: { person?: Personnel; onClose: () => void }) {
  const { t } = useTranslation()
  const { message } = App.useApp()
  const [form] = Form.useForm<LoginValues>()
  const grant = useGrantLogin()

  const onFinish = (values: LoginValues) => {
    if (!person) return
    grant.mutate(
      { id: person.id, ...values },
      {
        onSuccess: (result) => {
          message.success(result.newAccount ? t('customers.portalCreated') : t('personnel.loginLinked'))
          onClose()
        },
        onError: (error) => {
          if (!applyFieldErrors(form, error, t)) message.error(errorMessage(error, t))
        },
      },
    )
  }

  return (
    <Modal
      open={!!person}
      title={t('personnel.loginFor', { name: person?.fullName })}
      onCancel={onClose}
      onOk={() => form.submit()}
      okText={t('customers.grantPortal')}
      cancelText={t('common.cancel')}
      confirmLoading={grant.isPending}
      destroyOnHidden
    >
      <Typography.Paragraph type="secondary">{t('personnel.loginHint', { phone: person?.phone ?? '—' })}</Typography.Paragraph>
      <Form<LoginValues> form={form} layout="vertical" onFinish={onFinish}>
        <Form.Item label={t('session.role')}>
          <Typography.Text>{t('roles.worker')}</Typography.Text>
        </Form.Item>
        <Form.Item name="password" label={t('customers.initialPassword')} extra={t('customers.initialPasswordHint')}>
          <Input.Password autoComplete="new-password" />
        </Form.Item>
      </Form>
    </Modal>
  )
}
