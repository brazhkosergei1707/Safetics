import { PlusOutlined, SettingOutlined } from '@ant-design/icons'
import { App, Button, Empty, Flex, Input, Popconfirm, Table, Tabs, Tag, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import dayjs from 'dayjs'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  type Asset,
  type AssetType,
  type FieldDefinition,
  useArchiveAsset,
  useAssets,
  useAssetTypes,
  useInstallTemplate,
} from '../../api/assets'
import { hasPermission, useSession } from '../../api/auth'
import { ApiError } from '../../api/client'
import { localized } from '../../i18n/localized'
import { ServiceLevelTag } from './ServiceLevelTag'
import { AssetFormDrawer } from './AssetFormDrawer'
import { AssetTypesDrawer } from './AssetTypesDrawer'

/** Скільки полів типу показувати в таблиці; решта — у формі. */
const VISIBLE_FIELDS = 5

export function AssetsPage() {
  const { t, i18n } = useTranslation()
  const { message } = App.useApp()
  const { data: session } = useSession()
  const types = useAssetTypes()
  const installTemplate = useInstallTemplate()
  const archive = useArchiveAsset()

  const [activeTypeId, setActiveTypeId] = useState<string>()
  const [query, setQuery] = useState('')
  const [editing, setEditing] = useState<{ open: boolean; asset?: Asset }>({ open: false })
  const [typesOpen, setTypesOpen] = useState(false)

  const canEdit = hasPermission(session, 'asset.edit')
  const canManageTypes = hasPermission(session, 'catalog.manage')

  const typeList = types.data ?? []
  const activeType = typeList.find((type) => type.id === activeTypeId) ?? sortByCategory(typeList)[0]
  const assets = useAssets({ typeId: activeType?.id, q: query }, !!activeType)

  const columns = useMemo(
    () => (activeType ? buildColumns(activeType, i18n.language, t, canEdit, {
      edit: (asset) => setEditing({ open: true, asset }),
      archive: (asset) =>
        archive.mutate(asset.id, {
          onSuccess: () => message.success(t('assets.archived')),
          onError: (error) =>
            message.error(
              error instanceof ApiError && error.code === 'has_active_children'
                ? t('assets.hasActiveChildren')
                : t('errors.serverUnavailable'),
            ),
        }),
    }) : []),
    [activeType, i18n.language, t, canEdit, archive, message],
  )

  if (types.isSuccess && typeList.length === 0) {
    return (
      <>
        <Typography.Title level={2}>{t('nav.assets')}</Typography.Title>
        <Empty description={t('assets.noTypes')}>
          {canManageTypes && (
            <Flex gap={8} justify="center">
              <Button icon={<SettingOutlined />} onClick={() => setTypesOpen(true)}>
                {t('assetTypes.new')}
              </Button>
              <Button loading={installTemplate.isPending} onClick={() => installTemplate.mutate('exploration-drilling')}>
                {t('assets.installTemplate')}
              </Button>
            </Flex>
          )}
        </Empty>
        <AssetTypesDrawer open={typesOpen} onClose={() => setTypesOpen(false)} />
      </>
    )
  }

  return (
    <>
      <Flex justify="space-between" align="center" style={{ marginBottom: 8 }}>
        <Typography.Title level={2} style={{ margin: 0 }}>
          {t('nav.assets')}
        </Typography.Title>
        <Flex gap={8}>
          {canManageTypes && (
            <Button icon={<SettingOutlined />} onClick={() => setTypesOpen(true)}>
              {t('assetTypes.title')}
            </Button>
          )}
          {canEdit && activeType && (
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setEditing({ open: true })}>
              {t('assets.add')}
            </Button>
          )}
        </Flex>
      </Flex>

      <Tabs
        activeKey={activeType?.id}
        onChange={(key) => {
          setActiveTypeId(key)
          setQuery('')
        }}
        items={sortByCategory(typeList).map((type) => ({
          key: type.id,
          label: (
            <Flex gap={6} align="center">
              {localized(type.name, i18n.language)}
              {type.category === 'primary' && <Tag color="orange">{t('assets.primary')}</Tag>}
            </Flex>
          ),
        }))}
      />

      <Input.Search
        allowClear
        placeholder={t('assets.search')}
        onSearch={setQuery}
        style={{ maxWidth: 320, marginBottom: 16 }}
      />

      <Table<Asset>
        rowKey="id"
        size="middle"
        loading={types.isPending || assets.isPending}
        dataSource={assets.data?.items ?? []}
        columns={columns}
        pagination={{ pageSize: 50, hideOnSinglePage: true }}
        scroll={{ x: 'max-content' }}
      />

      <AssetTypesDrawer open={typesOpen} onClose={() => setTypesOpen(false)} />

      {activeType && (
        <AssetFormDrawer
          open={editing.open}
          type={activeType}
          allTypes={typeList}
          asset={editing.asset}
          onClose={() => setEditing({ open: false })}
        />
      )}
    </>
  )
}

/** Основне обладнання (бурові) — першим, далі допоміжне. */
function sortByCategory(types: AssetType[]): AssetType[] {
  return [...types].sort((a, b) => (a.category === b.category ? 0 : a.category === 'primary' ? -1 : 1))
}

function buildColumns(
  type: AssetType,
  language: string,
  t: (key: string) => string,
  canEdit: boolean,
  actions: { edit: (asset: Asset) => void; archive: (asset: Asset) => void },
): ColumnsType<Asset> {
  const columns: ColumnsType<Asset> = [
    {
      title: t('assets.code'),
      dataIndex: 'code',
      fixed: 'left',
      render: (code: string, asset) =>
        canEdit ? <Typography.Link onClick={() => actions.edit(asset)}>{code}</Typography.Link> : code,
    },
    { title: t('assets.name'), dataIndex: 'name' },
    {
      title: t('assets.service'),
      key: 'service',
      render: (_: unknown, asset: Asset) => <ServiceLevelTag level={asset.serviceLevel} count={asset.openServiceTasks} />,
    },
    {
      title: t('assets.status'),
      dataIndex: 'status',
      render: (code: string) => {
        const status = type.statuses.find((s) => s.code === code)
        return <Tag color={status?.color === 'default' ? undefined : status?.color}>{localized(status?.label, language) || code}</Tag>
      },
    },
    ...type.fields.slice(0, VISIBLE_FIELDS).map((field) => ({
      title: field.unit ? `${localized(field.label, language)}, ${field.unit}` : localized(field.label, language),
      key: field.key,
      align: field.type === 'number' || field.type === 'integer' ? ('right' as const) : undefined,
      render: (_: unknown, asset: Asset) => formatValue(field, asset.attributes[field.key], language),
    })),
  ]
  if (canEdit) {
    columns.push({
      key: 'actions',
      align: 'right',
      render: (_, asset) => (
        <Popconfirm title={t('assets.archiveConfirm')} onConfirm={() => actions.archive(asset)}>
          <Button type="link" size="small" danger>
            {t('assets.archive')}
          </Button>
        </Popconfirm>
      ),
    })
  }
  return columns
}

function formatValue(field: FieldDefinition, value: unknown, language: string): string {
  if (value === undefined || value === null || value === '') return ''
  switch (field.type) {
    case 'number':
    case 'integer':
      return Number(value).toLocaleString(language)
    case 'date':
      return dayjs(value as string).format('DD.MM.YYYY')
    case 'boolean':
      return value ? '✓' : '—'
    default:
      return String(value)
  }
}
