import { Button, Result, Spin } from 'antd'
import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Navigate, useLocation } from 'react-router'
import { useSession } from '../api/auth'
import { ApiError } from '../api/client'
import { CompanyPickerPage } from '../pages/CompanyPickerPage'

/**
 * Пропускає далі лише користувача з активною сесією і обраною компанією.
 * Немає сесії → сторінка входу. Кілька компаній і жодна не обрана → вибір компанії.
 * Адмін платформи на /platform проходить і без компанії.
 */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { t } = useTranslation()
  const session = useSession()
  const location = useLocation()

  if (session.isPending) {
    return <Spin size="large" fullscreen />
  }

  const unauthorized = session.error instanceof ApiError && session.error.status === 401
  if (session.isError && !unauthorized) {
    return (
      <Result
        status="error"
        title={t('errors.serverUnavailable')}
        extra={<Button onClick={() => session.refetch()}>{t('common.retry')}</Button>}
      />
    )
  }

  if (!session.data) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }

  // Адмін платформи працює зі сторінкою «Компанії» і без обраної компанії
  const onPlatformPage = location.pathname.startsWith('/platform')
  if (onPlatformPage) {
    return session.data.platformAdmin ? <>{children}</> : <Navigate to="/" replace />
  }
  if (!session.data.company) {
    return session.data.platformAdmin && session.data.companies.length === 0 ? (
      <Navigate to="/platform" replace />
    ) : (
      <CompanyPickerPage session={session.data} />
    )
  }

  return <>{children}</>
}
