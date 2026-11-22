import { PlusOutlined } from '@ant-design/icons'
import { App, Button, Drawer, Flex, Form, Input, InputNumber, Select, Switch, Table, Tabs, Tag } from 'antd'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { applyFieldErrors, errorMessage } from '../../api/formErrors'
import {
  type ActivityCategory,
  type ActivityCode,
  type ConsumableItem,
  useActivityCodes,
  useArchiveActivityCode,
  useArchiveConsumableItem,
  useConsumableItems,
  useSaveActivityCode,
  useSaveConsumableItem,
} from '../../api/reportCatalogs'
import { localized } from '../../i18n/localized'

const CATEGORIES: ActivityCategory[] = ['productive', 'contractor_npt', 'client_standby', 'other']
const CATEGORY_COLORS: Record<ActivityCategory, string> = {
  productive: 'green',
  contractor_npt: 'red',
  client_standby: 'orange',
  other: 'default',
}

interface CodeForm {
  code: string
  name: string
  category: ActivityCategory
  billable: boolean
  sortOrder?: number
}

interface ItemForm {
  code: string
  name: string
  unit: string
  serialTracked: boolean
}

/** Довідники рапорту: коди операцій (з категорією й «оплачуваний») і позиції витрат. catalog.manage. */
export function ReportCatalogsDrawer({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { t, i18n } = useTranslation()
  const lang = i18n.language
  const { message } = App.useApp()
  const codes = useActivityCodes(true, open)
  const items = useConsumableItems(true, open)
  const saveCode = useSaveActivityCode()
  const saveItem = useSaveConsumableItem()
  const archiveCode = useArchiveActivityCode()
  const archiveItem = useArchiveConsumableItem()
  const [tab, setTab] = useState<'codes' | 'items'>('codes')
  const [editingCode, setEditingCode] = useState<{ code?: ActivityCode } | null>(null)
  const [editingItem, setEditingItem] = useState<{ item?: ConsumableItem } | null>(null)
  const [codeForm] = Form.useForm<CodeForm>()
  const [itemForm] = Form.useForm<ItemForm>()
  const required = [{ required: true, message: t('validation.required') }]
  const editing = tab === 'codes' ? editingCode : editingItem

  const startCode = (code?: ActivityCode) => {
    codeForm.resetFields()
    codeForm.setFieldsValue(
      code
        ? { code: code.code, name: localized(code.name, lang), category: code.category, billable: code.billable, sortOrder: code.sortOrder }
        : { category: 'productive', billable: true },
    )
    setEditingCode({ code })
  }

  const startItem = (item?: ConsumableItem) => {
    itemForm.resetFields()
    itemForm.setFieldsValue(
      item
        ? { code: item.code, name: localized(item.name, lang), unit: item.unit, serialTracked: item.serialTracked }
        : { serialTracked: false },
    )
    setEditingItem({ item })
  }

  const onError = (form: typeof codeForm | typeof itemForm) => (error: Error) => {
    if (!applyFieldErrors(form, error, t)) message.error(errorMessage(error, t))
  }

  const submitCode = (values: CodeForm) =>
    saveCode.mutate(
      {
        id: editingCode?.code?.id,
        body: {
          code: values.code,
          name: { ...(editingCode?.code?.name ?? {}), [lang]: values.name },
          category: values.category,
          billable: values.billable,
          sortOrder: values.sortOrder,
        },
      },
      {
        onSuccess: () => {
          message.success(t('common.saved'))
          setEditingCode(null)
        },
        onError: onError(codeForm),
      },
    )

  const submitItem = (values: ItemForm) =>
    saveItem.mutate(
      {
        id: editingItem?.item?.id,
        body: {
          code: values.code,
          name: { ...(editingItem?.item?.name ?? {}), [lang]: values.name },
          unit: values.unit,
          serialTracked: values.serialTracked,
        },
      },
      {
        onSuccess: () => {
          message.success(t('common.saved'))
          setEditingItem(null)
        },
        onError: onError(itemForm),
      },
    )

  const close = () => {
    setEditingCode(null)
    setEditingItem(null)
    onClose()
  }

  const footer = editing && (
    <Flex justify="flex-end" gap={8}>
      <Button onClick={() => (tab === 'codes' ? setEditingCode(null) : setEditingItem(null))}>{t('common.cancel')}</Button>
      <Button
        type="primary"
        loading={saveCode.isPending || saveItem.isPending}
        onClick={() => (tab === 'codes' ? codeForm.submit() : itemForm.submit())}
      >
        {t('common.save')}
      </Button>
    </Flex>
  )

  const codesList = (
    <>
      <Flex justify="flex-end" style={{ marginBottom: 12 }}>
        <Button icon={<PlusOutlined />} onClick={() => startCode()}>
          {t('reportCatalogs.newCode')}
        </Button>
      </Flex>
      <Table<ActivityCode>
        rowKey="id"
        size="small"
        pagination={false}
        loading={codes.isPending}
        dataSource={codes.data ?? []}
        columns={[
          { title: t('projects.code'), dataIndex: 'code', width: 130 },
          {
            title: t('projects.name'),
            key: 'name',
            render: (_, c) => (
              <Flex gap={6} align="center">
                {localized(c.name, lang)}
                {c.archived && <Tag>{t('validation.archived')}</Tag>}
              </Flex>
            ),
          },
          {
            title: t('reportCatalogs.category'),
            dataIndex: 'category',
            render: (category: ActivityCategory) => (
              <Tag color={CATEGORY_COLORS[category]}>{t(`reportCatalogs.categories.${category}`)}</Tag>
            ),
          },
          {
            title: t('reportCatalogs.billable'),
            dataIndex: 'billable',
            render: (billable: boolean) => (billable ? t('common.yes') : t('common.no')),
          },
          {
            key: 'actions',
            align: 'right',
            render: (_, c) => (
              <Flex gap={4} justify="flex-end">
                <Button size="small" type="link" onClick={() => startCode(c)}>
                  {t('common.edit')}
                </Button>
                <Button size="small" type="link" onClick={() => archiveCode.mutate({ id: c.id, restore: c.archived })}>
                  {c.archived ? t('customers.restore') : t('assets.archive')}
                </Button>
              </Flex>
            ),
          },
        ]}
      />
    </>
  )

  const codeEditor = (
    <Form<CodeForm> form={codeForm} layout="vertical" onFinish={submitCode} requiredMark="optional">
      <Flex gap={12} wrap>
        <Form.Item name="code" label={t('projects.code')} rules={required} extra={t('reportCatalogs.codeHint')}>
          <Input disabled={!!editingCode?.code} style={{ width: 180 }} />
        </Form.Item>
        <Form.Item name="name" label={t('projects.name')} rules={required} style={{ flex: 1, minWidth: 220 }}>
          <Input />
        </Form.Item>
      </Flex>
      <Flex gap={12} wrap>
        <Form.Item name="category" label={t('reportCatalogs.category')} rules={required} extra={t('reportCatalogs.categoryHint')}>
          <Select
            style={{ width: 280 }}
            options={CATEGORIES.map((c) => ({ value: c, label: t(`reportCatalogs.categories.${c}`) }))}
            onChange={(c: ActivityCategory) => codeForm.setFieldValue('billable', c === 'productive' || c === 'client_standby')}
          />
        </Form.Item>
        <Form.Item name="billable" label={t('reportCatalogs.billable')} valuePropName="checked">
          <Switch />
        </Form.Item>
        <Form.Item name="sortOrder" label={t('reportCatalogs.sortOrder')}>
          <InputNumber min={0} style={{ width: 100 }} />
        </Form.Item>
      </Flex>
    </Form>
  )

  const itemsList = (
    <>
      <Flex justify="flex-end" style={{ marginBottom: 12 }}>
        <Button icon={<PlusOutlined />} onClick={() => startItem()}>
          {t('reportCatalogs.newItem')}
        </Button>
      </Flex>
      <Table<ConsumableItem>
        rowKey="id"
        size="small"
        pagination={false}
        loading={items.isPending}
        dataSource={items.data ?? []}
        columns={[
          { title: t('projects.code'), dataIndex: 'code', width: 140 },
          {
            title: t('projects.name'),
            key: 'name',
            render: (_, item) => (
              <Flex gap={6} align="center">
                {localized(item.name, lang)}
                {item.archived && <Tag>{t('validation.archived')}</Tag>}
              </Flex>
            ),
          },
          { title: t('reportCatalogs.unit'), dataIndex: 'unit', width: 80 },
          {
            title: t('reportCatalogs.serialTracked'),
            dataIndex: 'serialTracked',
            render: (serial: boolean) => (serial ? t('common.yes') : ''),
          },
          {
            key: 'actions',
            align: 'right',
            render: (_, item) => (
              <Flex gap={4} justify="flex-end">
                <Button size="small" type="link" onClick={() => startItem(item)}>
                  {t('common.edit')}
                </Button>
                <Button size="small" type="link" onClick={() => archiveItem.mutate({ id: item.id, restore: item.archived })}>
                  {item.archived ? t('customers.restore') : t('assets.archive')}
                </Button>
              </Flex>
            ),
          },
        ]}
      />
    </>
  )

  const itemEditor = (
    <Form<ItemForm> form={itemForm} layout="vertical" onFinish={submitItem} requiredMark="optional">
      <Flex gap={12} wrap>
        <Form.Item name="code" label={t('projects.code')} rules={required} extra={t('personnel.codeHint')}>
          <Input disabled={!!editingItem?.item} style={{ width: 180 }} />
        </Form.Item>
        <Form.Item name="name" label={t('projects.name')} rules={required} style={{ flex: 1, minWidth: 220 }}>
          <Input />
        </Form.Item>
      </Flex>
      <Flex gap={12} wrap>
        <Form.Item name="unit" label={t('reportCatalogs.unit')} rules={required} extra={t('reportCatalogs.unitHint')}>
          <Input maxLength={20} style={{ width: 120 }} />
        </Form.Item>
        <Form.Item name="serialTracked" label={t('reportCatalogs.serialTracked')} valuePropName="checked"
          extra={t('reportCatalogs.serialHint')}>
          <Switch />
        </Form.Item>
      </Flex>
    </Form>
  )

  return (
    <Drawer open={open} onClose={close} size={760} destroyOnHidden title={t('reportCatalogs.title')} footer={footer}>
      <Tabs
        activeKey={tab}
        onChange={(key) => setTab(key as 'codes' | 'items')}
        items={[
          { key: 'codes', label: t('reportCatalogs.codes'), children: editingCode ? codeEditor : codesList },
          { key: 'items', label: t('reportCatalogs.items'), children: editingItem ? itemEditor : itemsList },
        ]}
      />
    </Drawer>
  )
}
