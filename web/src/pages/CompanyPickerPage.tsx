import { Alert, Button, Card, Flex, List, Typography } from 'antd'
import { useTranslation } from 'react-i18next'
import { type SessionView, useLogout, useSwitchCompany } from '../api/auth'

/** Показується після входу, якщо користувач працює в кількох компаніях. */
export function CompanyPickerPage({ session }: { session: SessionView }) {
  const { t } = useTranslation()
  const switchCompany = useSwitchCompany()
  const logout = useLogout()

  return (
    <Flex justify="center" align="center" style={{ minHeight: '100vh', padding: 16 }}>
      <Card style={{ width: '100%', maxWidth: 460 }}>
        <Typography.Title level={4}>{t('company.choose')}</Typography.Title>
        <Typography.Paragraph type="secondary">{session.user.fullName}</Typography.Paragraph>

        {session.companies.length === 0 ? (
          <Alert type="warning" title={t('company.none')} showIcon />
        ) : (
          <List
            dataSource={session.companies}
            renderItem={(company) => (
              <List.Item
                actions={[
                  <Button
                    key="select"
                    type="primary"
                    loading={switchCompany.isPending && switchCompany.variables === company.id}
                    onClick={() => switchCompany.mutate(company.id)}
                  >
                    {t('company.select')}
                  </Button>,
                ]}
              >
                <List.Item.Meta
                  title={company.name}
                  description={company.role ? t(`roles.${company.role}`, company.role) : null}
                />
              </List.Item>
            )}
          />
        )}

        {switchCompany.isError && (
          <Alert type="error" title={t('errors.serverUnavailable')} showIcon style={{ marginTop: 16 }} />
        )}

        <Button type="link" onClick={() => logout.mutate()} style={{ paddingLeft: 0, marginTop: 16 }}>
          {t('auth.logout')}
        </Button>
      </Card>
    </Flex>
  )
}
