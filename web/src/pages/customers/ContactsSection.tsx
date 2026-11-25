import { KeyOutlined, PlusOutlined } from '@ant-design/icons'
import { App, Button, Flex, Form, Input, List, Modal, Popconfirm, Select, Tag, Typography } from 'antd'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { hasPermission, useSession } from '../../api/auth'
import {
  type Contact,
  type ContactRequest,
  type Customer,
  type PortalRole,
  useArchiveContact,
  useContacts,
  useGrantPortalAccess,
  useRevokePortalAccess,
  useSaveContact,
} from '../../api/customers'
import { applyFieldErrors, errorMessage } from '../../api/formErrors'

/**
 * Контактні особи замовника. Вхід у портал видає той, хто веде проєкти (project.manage):
 * представник замовника перевіряє рапорти, менеджер замовника затверджує місячний звіт.
 */
export function ContactsSection({ customer, canEdit }: { customer: Customer; canEdit: boolean }) {
  const { t } = useTranslation()
  const { message } = App.useApp()
  const { data: session } = useSession()
  const contacts = useContacts(customer.id)
  const save = useSaveContact()
  const archive = useArchiveContact()
  const revoke = useRevokePortalAccess()
  const [contactForm] = Form.useForm<ContactRequest>()
  const [editing, setEditing] = useState<{ open: boolean; contact?: Contact }>({ open: false })
  const [portalFor, setPortalFor] = useState<Contact>()
  const canGrant = hasPermission(session, 'project.manage')

  const openContact = (contact?: Contact) => {
    contactForm.resetFields()
    if (contact) contactForm.setFieldsValue(contact)
    setEditing({ open: true, contact })
  }

  const onSaveContact = (values: ContactRequest) => {
    save.mutate(
      { customerId: customer.id, id: editing.contact?.id, body: { ...values, version: editing.contact?.version } },
      {
        onSuccess: () => setEditing({ open: false }),
        onError: (error) => {
          if (!applyFieldErrors(contactForm, error, t)) message.error(errorMessage(error, t))
        },
      },
    )
  }

  return (
    <>
      <List<Contact>
        loading={contacts.isPending}
        dataSource={contacts.data ?? []}
        locale={{ emptyText: t('customers.noContacts') }}
        renderItem={(contact) => (
          <List.Item
            actions={[
              canGrant && !contact.portalRole && (
                <Button key="grant" size="small" icon={<KeyOutlined />} onClick={() => setPortalFor(contact)}>
                  {t('customers.portalAccess')}
                </Button>
              ),
              canGrant && contact.portalRole && (
                <Popconfirm
                  key="revoke"
                  title={t('customers.revokePortalConfirm')}
                  onConfirm={() => revoke.mutate(contact.id, { onError: (e) => message.error(errorMessage(e, t)) })}
                >
                  <Button size="small" danger>
                    {t('customers.revokePortal')}
                  </Button>
                </Popconfirm>
              ),
              canEdit && (
                <Popconfirm
                  key="archive"
                  title={t('customers.archiveContactConfirm')}
                  onConfirm={() => archive.mutate(contact.id)}
                >
                  <Button size="small" type="link" danger>
                    {t('assets.archive')}
                  </Button>
                </Popconfirm>
              ),
            ].filter(Boolean)}
          >
            <List.Item.Meta
              title={
                <Flex gap={8} align="center">
                  {canEdit ? (
                    <Typography.Link onClick={() => openContact(contact)}>{contact.fullName}</Typography.Link>
                  ) : (
                    contact.fullName
                  )}
                  {contact.portalRole && <Tag color="blue">{t(`roles.${contact.portalRole}`)}</Tag>}
                </Flex>
              }
              description={[contact.jobTitle, contact.phone, contact.email].filter(Boolean).join(' · ')}
            />
          </List.Item>
        )}
      />
      {canEdit && (
        <Button type="dashed" icon={<PlusOutlined />} onClick={() => openContact()} style={{ marginTop: 8 }}>
          {t('customers.addContact')}
        </Button>
      )}

      <Modal
        open={editing.open}
        title={editing.contact ? editing.contact.fullName : t('customers.addContact')}
        onCancel={() => setEditing({ open: false })}
        onOk={() => contactForm.submit()}
        okText={t('common.save')}
        cancelText={t('common.cancel')}
        confirmLoading={save.isPending}
        destroyOnHidden
      >
        <Form<ContactRequest> name="contact" form={contactForm} layout="vertical" onFinish={onSaveContact} requiredMark="optional">
          <Form.Item
            name="fullName"
            label={t('customers.fullName')}
            rules={[{ required: true, message: t('validation.required') }]}
          >
            <Input />
          </Form.Item>
          <Form.Item name="jobTitle" label={t('customers.jobTitle')}>
            <Input placeholder={t('customers.jobTitleHint')} />
          </Form.Item>
          <Form.Item name="phone" label={t('customers.phone')} extra={t('customers.phoneHint')}>
            <Input placeholder="+55 94 99999-0000" />
          </Form.Item>
          <Form.Item name="email" label={t('customers.email')}>
            <Input type="email" />
          </Form.Item>
        </Form>
      </Modal>

      <PortalAccessModal contact={portalFor} onClose={() => setPortalFor(undefined)} />
    </>
  )
}

interface PortalValues {
  role: PortalRole
  password?: string
}

function PortalAccessModal({ contact, onClose }: { contact?: Contact; onClose: () => void }) {
  const { t } = useTranslation()
  const { message } = App.useApp()
  const [form] = Form.useForm<PortalValues>()
  const grant = useGrantPortalAccess()

  const onFinish = (values: PortalValues) => {
    if (!contact) return
    grant.mutate(
      { id: contact.id, ...values },
      {
        onSuccess: (result) => {
          message.success(result.newAccount ? t('customers.portalCreated') : t('customers.portalExisting'))
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
      open={!!contact}
      title={t('customers.portalAccessFor', { name: contact?.fullName })}
      onCancel={onClose}
      onOk={() => form.submit()}
      okText={t('customers.grantPortal')}
      cancelText={t('common.cancel')}
      confirmLoading={grant.isPending}
      destroyOnHidden
    >
      <Typography.Paragraph type="secondary">
        {t('customers.portalHint', { phone: contact?.phone ?? '—' })}
      </Typography.Paragraph>
      <Form<PortalValues> name="portal" form={form} layout="vertical" onFinish={onFinish} initialValues={{ role: 'client_rep' }}>
        <Form.Item name="role" label={t('session.role')} rules={[{ required: true }]}>
          <Select
            options={[
              { value: 'client_rep', label: `${t('roles.client_rep')} — ${t('customers.clientRepHint')}` },
              { value: 'client_manager', label: `${t('roles.client_manager')} — ${t('customers.clientManagerHint')}` },
            ]}
          />
        </Form.Item>
        <Form.Item name="password" label={t('customers.initialPassword')} extra={t('customers.initialPasswordHint')}>
          <Input.Password autoComplete="new-password" />
        </Form.Item>
      </Form>
    </Modal>
  )
}
