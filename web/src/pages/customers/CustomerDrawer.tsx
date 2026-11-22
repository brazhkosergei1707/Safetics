import { App, Button, Divider, Drawer, Flex, Form, Input, Popconfirm } from 'antd'
import { useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { ApiError } from '../../api/client'
import { type Customer, type CustomerRequest, useArchiveCustomer, useSaveCustomer } from '../../api/customers'
import { applyFieldErrors, errorMessage } from '../../api/formErrors'
import { ContactsSection } from './ContactsSection'

interface Props {
  open: boolean
  /** undefined — новий замовник */
  customer?: Customer
  canEdit: boolean
  onClose: () => void
  /** Після створення панель лишається відкритою, щоб одразу додати контактних осіб. */
  onCreated: (customer: Customer) => void
}

export function CustomerDrawer({ open, customer, canEdit, onClose, onCreated }: Props) {
  const { t } = useTranslation()
  const { message } = App.useApp()
  const [form] = Form.useForm<CustomerRequest>()
  const save = useSaveCustomer()
  const archive = useArchiveCustomer()

  useEffect(() => {
    if (!open) return
    form.resetFields()
    if (customer) form.setFieldsValue(customer)
  }, [open, customer, form])

  const onFinish = (values: CustomerRequest) => {
    save.mutate(
      {
        id: customer?.id,
        body: { ...values, country: values.country?.toUpperCase() || undefined, version: customer?.version },
      },
      {
        onSuccess: (saved) => {
          message.success(t('common.saved'))
          if (customer) onClose()
          else onCreated(saved)
        },
        onError: (error) => {
          if (error instanceof ApiError && error.code === 'duplicate') {
            form.setFields([{ name: 'name', errors: [t('validation.duplicate')] }])
          } else if (!applyFieldErrors(form, error, t)) {
            message.error(errorMessage(error, t))
          }
        },
      },
    )
  }

  const onArchive = () => {
    if (!customer) return
    archive.mutate(
      { id: customer.id, restore: customer.archived },
      {
        onSuccess: () => onClose(),
        onError: (error) => message.error(errorMessage(error, t)),
      },
    )
  }

  return (
    <Drawer
      open={open}
      onClose={onClose}
      size={560}
      destroyOnHidden
      title={customer ? customer.name : t('customers.new')}
      footer={
        canEdit && (
          <Flex justify="space-between" gap={8}>
            {customer ? (
              <Popconfirm
                title={customer.archived ? t('customers.restoreConfirm') : t('customers.archiveConfirm')}
                onConfirm={onArchive}
              >
                <Button danger={!customer.archived} loading={archive.isPending}>
                  {customer.archived ? t('customers.restore') : t('assets.archive')}
                </Button>
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
        )
      }
    >
      <Form<CustomerRequest> form={form} layout="vertical" onFinish={onFinish} requiredMark="optional" disabled={!canEdit}>
        <Form.Item name="name" label={t('customers.name')} rules={[{ required: true, message: t('validation.required') }]}>
          <Input maxLength={200} />
        </Form.Item>
        <Form.Item name="legalCode" label={t('customers.legalCode')}>
          <Input />
        </Form.Item>
        <Form.Item name="country" label={t('customers.country')} extra={t('customers.countryHint')}>
          <Input maxLength={2} style={{ width: 80, textTransform: 'uppercase' }} />
        </Form.Item>
        <Form.Item name="phone" label={t('customers.phone')}>
          <Input />
        </Form.Item>
        <Form.Item name="email" label={t('customers.email')}>
          <Input type="email" />
        </Form.Item>
        <Form.Item name="notes" label={t('customers.notes')}>
          <Input.TextArea rows={3} />
        </Form.Item>
      </Form>

      {customer && (
        <>
          <Divider titlePlacement="start">{t('customers.contacts')}</Divider>
          <ContactsSection customer={customer} canEdit={canEdit} />
        </>
      )}
    </Drawer>
  )
}
