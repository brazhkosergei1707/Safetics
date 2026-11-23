import { App, Button, Empty, Flex, Input, List, Modal, Tag, Typography } from 'antd'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { type LibraryType, useAssetTypeLibrary, useInstallFromLibrary } from '../../api/assets'
import { errorMessage } from '../../api/formErrors'
import { localized } from '../../i18n/localized'

/**
 * Бібліотека: типи обладнання, які створили інші компанії (без назв компаній). «Додати» копіює тип
 * у компанію — далі його можна змінювати, оригінал не зміниться.
 */
export function AssetTypeLibraryModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { t, i18n } = useTranslation()
  const lang = i18n.language
  const { message } = App.useApp()
  const library = useAssetTypeLibrary(open)
  const install = useInstallFromLibrary()
  const [query, setQuery] = useState('')

  const items = useMemo(() => {
    const q = query.trim().toLowerCase()
    return (library.data ?? []).filter(
      (e) => !q || e.code.includes(q) || Object.values(e.name).some((n) => n.toLowerCase().includes(q)),
    )
  }, [library.data, query])

  const add = (entry: LibraryType) =>
    install.mutate(entry.sourceId, {
      onSuccess: (type) => {
        message.success(t('assetTypes.libraryAdded', { name: localized(type.name, lang) }))
        void library.refetch()
      },
      onError: (e) => message.error(errorMessage(e, t)),
    })

  return (
    <Modal open={open} onCancel={onClose} footer={null} width={720} title={t('assetTypes.library')} destroyOnHidden>
      <Typography.Paragraph type="secondary">{t('assetTypes.libraryHint')}</Typography.Paragraph>
      <Input.Search allowClear placeholder={t('assets.search')} onChange={(e) => setQuery(e.target.value)} style={{ marginBottom: 12 }} />
      <List<LibraryType>
        loading={library.isPending}
        dataSource={items}
        locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('assetTypes.libraryEmpty')} /> }}
        pagination={{ pageSize: 10, hideOnSinglePage: true }}
        renderItem={(entry) => (
          <List.Item
            actions={[
              entry.exists ? (
                <Tag key="exists">{t('assetTypes.libraryExists')}</Tag>
              ) : (
                <Button
                  key="add"
                  type="primary"
                  ghost
                  size="small"
                  loading={install.isPending && install.variables === entry.sourceId}
                  onClick={() => add(entry)}
                >
                  {t('assetTypes.libraryAdd')}
                </Button>
              ),
            ]}
          >
            <List.Item.Meta
              title={
                <Flex gap={8} align="center">
                  {localized(entry.name, lang)}
                  <Tag color={entry.category === 'primary' ? 'orange' : undefined}>{t(`assets.${entry.category}`)}</Tag>
                </Flex>
              }
              description={t('assetTypes.libraryMeta', {
                code: entry.code,
                fields: entry.fieldsCount,
                checklist: entry.checklistCount,
                companies: entry.companies,
              })}
            />
          </List.Item>
        )}
      />
    </Modal>
  )
}
