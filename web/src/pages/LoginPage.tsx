import { LockOutlined, PhoneOutlined } from '@ant-design/icons'
import { Alert, Button, Card, Flex, Form, Input, Select, Typography } from 'antd'
import { useTranslation } from 'react-i18next'
import { Navigate, useLocation, useNavigate } from 'react-router'
import { useLogin, useSession } from '../api/auth'
import { ApiError } from '../api/client'
import { LANGUAGES } from '../i18n'

interface LoginForm {
  phone: string
  password: string
}

export function LoginPage() {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const location = useLocation()
  const session = useSession()
  const login = useLogin()

  const from = (location.state as { from?: string } | null)?.from ?? '/'

  if (session.data) {
    return <Navigate to={from} replace />
  }

  const onFinish = (values: LoginForm) => {
    login.mutate(values, { onSuccess: () => navigate(from, { replace: true }) })
  }

  const errorText =
    login.error instanceof ApiError && login.error.status === 401
      ? t('login.invalidCredentials')
      : login.error
        ? t('errors.serverUnavailable')
        : null

  return (
    <Flex justify="center" align="center" style={{ minHeight: '100vh', padding: 16 }}>
      <Card style={{ width: '100%', maxWidth: 380 }}>
        <Flex justify="space-between" align="center" style={{ marginBottom: 24 }}>
          <Typography.Title level={3} style={{ margin: 0 }}>
            Safetics
          </Typography.Title>
          <Select
            size="small"
            value={i18n.language}
            onChange={(lng) => i18n.changeLanguage(lng)}
            options={LANGUAGES.map((l) => ({ value: l.code, label: l.label }))}
            style={{ width: 120 }}
          />
        </Flex>

        <Form<LoginForm> layout="vertical" onFinish={onFinish} requiredMark={false}>
          <Form.Item
            name="phone"
            label={t('login.phone')}
            rules={[{ required: true, message: t('login.phoneRequired') }]}
          >
            <Input prefix={<PhoneOutlined />} placeholder="+380…" autoComplete="username" inputMode="tel" />
          </Form.Item>
          <Form.Item
            name="password"
            label={t('login.password')}
            rules={[{ required: true, message: t('login.passwordRequired') }]}
          >
            <Input.Password prefix={<LockOutlined />} autoComplete="current-password" />
          </Form.Item>

          {errorText && <Alert type="error" title={errorText} showIcon style={{ marginBottom: 16 }} />}

          <Button type="primary" htmlType="submit" block loading={login.isPending}>
            {t('login.submit')}
          </Button>
        </Form>
      </Card>
    </Flex>
  )
}
