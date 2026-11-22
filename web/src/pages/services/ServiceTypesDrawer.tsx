import { PlusOutlined } from '@ant-design/icons'
import { App, Button, Divider, Drawer, Flex, Form, Input, List, Select, Tag } from 'antd'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { applyFieldErrors, errorMessage } from '../../api/formErrors'
import { type ServiceType, useArchiveServiceType, useSaveServiceType, useServiceTypes } from '../../api/services'
import { useTaskTypes } from '../../api/tasks'
import { FieldsEditor } from '../../components/FieldsEditor'
import { type FieldFormValues, fieldsFromForm, fieldsToForm } from '../../components/fieldsForm'
import { localized } from '../../i18n/localized'

interface FormValues {
  code: string
  name: string
  fields: FieldFormValues[]
  taskTypeId?: string
  unit?: ServiceType['unit']
}

const UNITS = ['m', 'm2', 'm3', 'pcs', 'order', 'h'] as const

/** Типи послуг компанії (свердловина, замовлення…): назва, поля й тип головної задачі. catalog.manage. */
export function ServiceTypesDrawer({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { t, i18n } = useTranslation()
  const { message } = App.useApp()
  const types = useServiceTypes(true)
  const save = useSaveServiceType()
  const archive = useArchiveServiceType()
  const taskTypes = useTaskTypes(false, open)
  const [form] = Form.useForm<FormValues>()
  const [editing, setEditing] = useState<{ type?: ServiceType } | null>(null)
  const lang = i18n.language

  const startEdit = (type?: ServiceType) => {
    form.resetFields()
    form.setFieldsValue(
      type
        ? { code: type.code, name: localized(type.name, lang), fields: fieldsToForm(type.fields, lang), taskTypeId: type.taskTypeId, unit: type.unit }
        : { fields: [] },
    )
    setEditing({ type })
  }

  const onFinish = (values: FormValues) => {
    save.mutate(
      {
        id: editing?.type?.id,
        body: {
          code: values.code,
          name: { ...(editing?.type?.name ?? {}), [lang]: values.name },
          fields: fieldsFromForm(values.fields, lang),
          taskTypeId: values.taskTypeId,
          unit: values.unit,
        },
      },
      {
        onSuccess: () => {
          message.success(t('common.saved'))
          setEditing(null)
        },
        onError: (error) => {
          if (!applyFieldErrors(form, error, t)) message.error(errorMessage(error, t))
        },
      },
    )
  }

  const required = [{ required: true, message: t('validation.required') }]

  return (
    <Drawer
      open={open}
      onClose={() => {
        setEditing(null)
        onClose()
      }}
      size={720}
      destroyOnHidden
      title={editing ? (editing.type ? localized(editing.type.name, lang) : t('serviceTypes.new')) : t('serviceTypes.title')}
      footer={
        editing && (
          <Flex justify="flex-end" gap={8}>
            <Button onClick={() => setEditing(null)}>{t('common.cancel')}</Button>
            <Button type="primary" loading={save.isPending} onClick={() => form.submit()}>
              {t('common.save')}
            </Button>
          </Flex>
        )
      }
    >
      {!editing ? (
        <>
          <Flex justify="flex-end" style={{ marginBottom: 12 }}>
            <Button icon={<PlusOutlined />} onClick={() => startEdit()}>
              {t('serviceTypes.new')}
            </Button>
          </Flex>
          <List<ServiceType>
            loading={types.isPending}
            dataSource={types.data ?? []}
            renderItem={(type) => (
              <List.Item
                actions={[
                  <Button key="edit" type="link" size="small" onClick={() => startEdit(type)}>
                    {t('common.edit')}
                  </Button>,
                  <Button key="archive" type="link" size="small" onClick={() => archive.mutate({ id: type.id, restore: type.archived })}>
                    {type.archived ? t('customers.restore') : t('assets.archive')}
                  </Button>,
                ]}
              >
                <List.Item.Meta
                  title={
                    <Flex gap={8} align="center">
                      {localized(type.name, lang)}
                      {type.archived && <Tag>{t('validation.archived')}</Tag>}
                    </Flex>
                  }
                  description={`${type.code} · ${t('assetTypes.fieldsCount', { count: type.fields.length })}`}
                />
              </List.Item>
            )}
          />
        </>
      ) : (
        <Form<FormValues> form={form} layout="vertical" onFinish={onFinish} requiredMark="optional">
          <Flex gap={12} wrap>
            <Form.Item name="name" label={t('projects.name')} rules={required} style={{ flex: 1, minWidth: 220 }}>
              <Input />
            </Form.Item>
            <Form.Item name="code" label={t('projects.code')} rules={required} extra={t('personnel.codeHint')}>
              <Input disabled={!!editing.type} style={{ width: 180 }} />
            </Form.Item>
          </Flex>
          <Form.Item name="taskTypeId" label={t('serviceTypes.taskType')} extra={t('serviceTypes.taskTypeHint')}>
            <Select
              allowClear={!editing.type}
              placeholder={t('serviceTypes.taskTypeAuto')}
              loading={taskTypes.isPending}
              options={(taskTypes.data ?? [])
                .filter((tt) => tt.subjectKind === 'service' && tt.syncService)
                .map((tt) => ({ value: tt.id, label: localized(tt.name, lang) }))}
            />
          </Form.Item>
          <Form.Item name="unit" label={t('serviceTypes.unit')} extra={t('serviceTypes.unitHint')}>
            <Select allowClear style={{ width: 200 }} options={UNITS.map((u) => ({ value: u, label: t(`serviceTypes.units.${u}`) }))} />
          </Form.Item>
          <Divider titlePlacement="start">{t('assetTypes.fields')}</Divider>
          <FieldsEditor form={form} />
        </Form>
      )}
    </Drawer>
  )
}
