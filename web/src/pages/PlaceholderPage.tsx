import { Empty, Typography } from 'antd'
import { useTranslation } from 'react-i18next'

export function PlaceholderPage({ titleKey }: { titleKey: string }) {
  const { t } = useTranslation()
  return (
    <>
      <Typography.Title level={2}>{t(titleKey)}</Typography.Title>
      <Empty description={t('common.comingSoon')} />
    </>
  )
}
