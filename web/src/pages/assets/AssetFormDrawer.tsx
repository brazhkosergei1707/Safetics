import { App, Button, DatePicker, Drawer, Flex, Form, Input, InputNumber, Select, Switch } from 'antd'
import dayjs from 'dayjs'
import { useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import {
  type Asset,
  type AssetType,
  type FieldDefinition,
  useAssets,
  useCreateAsset,
  useUpdateAsset,
} from '../../api/assets'
import { ApiError } from '../../api/client'
import { localized } from '../../i18n/localized'

interface FormValues {
  code: string
  name?: string
  status: string
  parentId?: string
  attributes: Record<string, unknown>
}

interface Props {
  open: boolean
  type: AssetType
  allTypes: AssetType[]
  /** undefined — створення нового об'єкта */
  asset?: Asset
  onClose: () => void
}

/** Форма об'єкта: поля будуються зі схеми типу (fields), тож нові типи не потребують коду. */
export function AssetFormDrawer({ open, type, allTypes, asset, onClose }: Props) {
  const { t, i18n } = useTranslation()
  const { message } = App.useApp()
  const [form] = Form.useForm<FormValues>()
  const create = useCreateAsset()
  const update = useUpdateAsset()

  // Типи, у які можна вкласти цей тип (табір → ємність ПММ)
  const parentType = allTypes.find((candidate) => candidate.allowedChildTypes.includes(type.code))
  const parents = useAssets({ typeId: parentType?.id }, open && !!parentType)

  useEffect(() => {
    if (!open) return
    form.resetFields()
    form.setFieldsValue(
      asset
        ? {
            code: asset.code,
            name: asset.name,
            status: asset.status,
            parentId: asset.parentId,
            attributes: asset.attributes,
          }
        : { status: type.statuses[0]?.code, attributes: {} },
    )
  }, [open, asset, type, form])

  const onFinish = (values: FormValues) => {
    const attributes = Object.fromEntries(
      Object.entries(values.attributes ?? {}).filter(([, v]) => v !== undefined && v !== null && v !== ''),
    )
    const onError = (error: Error) => showErrors(error)
    const onSuccess = () => {
      message.success(t('common.saved'))
      onClose()
    }
    if (asset) {
      update.mutate(
        {
          id: asset.id,
          body: { ...values, attributes, version: asset.version },
        },
        { onSuccess, onError },
      )
    } else {
      create.mutate({ ...values, typeId: type.id, attributes }, { onSuccess, onError })
    }
  }

  /** Помилки сервера показуються біля відповідних полів форми. */
  const showErrors = (error: Error) => {
    if (!(error instanceof ApiError)) {
      message.error(t('errors.serverUnavailable'))
      return
    }
    if (error.code === 'stale_version') {
      message.error(t('assets.staleVersion'))
      return
    }
    if (error.code === 'duplicate') {
      form.setFields([{ name: 'code', errors: [t('validation.duplicate')] }])
      return
    }
    const fieldErrors = Object.entries(error.fields).map(([key, code]) => ({
      name: key.startsWith('attributes.') ? ['attributes', key.slice('attributes.'.length)] : key,
      errors: [t(`validation.${code}`, { defaultValue: code })],
    }))
    if (fieldErrors.length > 0) {
      form.setFields(fieldErrors)
    } else {
      message.error(t('errors.serverUnavailable'))
    }
  }

  const typeName = localized(type.name, i18n.language)

  return (
    <Drawer
      open={open}
      onClose={onClose}
      size={480}
      destroyOnHidden
      title={asset ? `${typeName} ${asset.code}` : t('assets.new', { type: typeName })}
      footer={
        <Flex justify="flex-end" gap={8}>
          <Button onClick={onClose}>{t('common.cancel')}</Button>
          <Button type="primary" loading={create.isPending || update.isPending} onClick={() => form.submit()}>
            {t('common.save')}
          </Button>
        </Flex>
      }
    >
      <Form<FormValues> form={form} layout="vertical" onFinish={onFinish} requiredMark="optional">
        <Form.Item name="code" label={t('assets.code')} rules={[{ required: true, message: t('validation.required') }]}>
          <Input maxLength={100} />
        </Form.Item>
        <Form.Item name="name" label={t('assets.name')}>
          <Input maxLength={200} />
        </Form.Item>
        <Form.Item name="status" label={t('assets.status')} rules={[{ required: true }]}>
          <Select
            options={type.statuses.map((s) => ({ value: s.code, label: localized(s.label, i18n.language) }))}
          />
        </Form.Item>
        {parentType && (
          <Form.Item name="parentId" label={localized(parentType.name, i18n.language)}>
            <Select
              allowClear
              loading={parents.isPending}
              options={(parents.data?.items ?? [])
                .filter((p) => p.id !== asset?.id)
                .map((p) => ({ value: p.id, label: p.name ? `${p.code} · ${p.name}` : p.code }))}
            />
          </Form.Item>
        )}

        {type.fields.map((field) => (
          <AttributeField key={field.key} field={field} language={i18n.language} />
        ))}
      </Form>
    </Drawer>
  )
}

function AttributeField({ field, language }: { field: FieldDefinition; language: string }) {
  const { t } = useTranslation()
  const name = ['attributes', field.key]
  const label = localized(field.label, language)
  const rules = field.required ? [{ required: true, message: t('validation.required') }] : []

  switch (field.type) {
    case 'number':
    case 'integer':
      return (
        <Form.Item name={name} label={label} rules={rules}>
          <InputNumber
            style={{ width: '100%' }}
            min={field.min}
            max={field.max}
            precision={field.type === 'integer' ? 0 : undefined}
            suffix={field.unit}
            decimalSeparator=","
          />
        </Form.Item>
      )
    case 'boolean':
      return (
        <Form.Item name={name} label={label} valuePropName="checked">
          <Switch />
        </Form.Item>
      )
    case 'date':
      return (
        <Form.Item
          name={name}
          label={label}
          rules={rules}
          getValueProps={(value) => ({ value: value ? dayjs(value as string) : null })}
          normalize={(value: dayjs.Dayjs | null) => (value ? value.format('YYYY-MM-DD') : null)}
        >
          <DatePicker style={{ width: '100%' }} format="DD.MM.YYYY" />
        </Form.Item>
      )
    case 'enum':
      return (
        <Form.Item name={name} label={label} rules={rules}>
          <Select allowClear options={(field.options ?? []).map((o) => ({ value: o, label: o }))} />
        </Form.Item>
      )
    case 'text':
      return (
        <Form.Item name={name} label={label} rules={rules}>
          <Input.TextArea rows={3} maxLength={5000} />
        </Form.Item>
      )
    default:
      return (
        <Form.Item name={name} label={label} rules={rules}>
          <Input maxLength={500} suffix={field.unit} />
        </Form.Item>
      )
  }
}
