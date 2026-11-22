import { Tag, Tooltip } from 'antd'
import { useTranslation } from 'react-i18next'

const COLORS: Record<string, string> = { ok: 'green', low: 'blue', medium: 'orange', high: 'red' }

/** Сервісний статус обладнання: справне або є відкриті задачі «Сервіс» (за найвищою критичністю). */
export function ServiceLevelTag({ level, count }: { level: string; count?: number }) {
  const { t } = useTranslation()
  const tag = (
    <Tag color={COLORS[level]} style={{ marginInlineEnd: 0 }}>
      {t(`assets.serviceLevels.${level}`)}
      {count ? ` · ${count}` : ''}
    </Tag>
  )
  return level === 'ok' ? tag : <Tooltip title={t('assets.serviceHint')}>{tag}</Tooltip>
}
