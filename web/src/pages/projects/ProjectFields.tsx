import { DatePicker, Form, Input, Select } from 'antd'
import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { hasPermission, useSession } from '../../api/auth'
import { useCustomers } from '../../api/customers'
import { useMembers } from '../../api/projects'

/** Часові пояси IANA з браузера + UTC. */
function timeZones(): string[] {
  const zones = Intl.supportedValuesOf('timeZone').filter((zone) => zone.includes('/'))
  return ['UTC', ...zones]
}

/** Спільні поля проєкту для створення й редагування. Дати — dayjs (перетворює власник форми). */
export function ProjectFields() {
  const { t } = useTranslation()
  const { data: session } = useSession()
  const customers = useCustomers({}, hasPermission(session, 'client.view'))
  // Відповідального менеджера призначає той, хто створює проєкти
  const assignsManager = hasPermission(session, 'project.create')
  const members = useMembers(assignsManager)
  const managers = (members.data ?? []).filter(
    (m) => !m.portal && m.status === 'active' && m.role === 'manager',
  )
  const zones = useMemo(() => timeZones(), [])
  const required = [{ required: true, message: t('validation.required') }]

  return (
    <>
      <Form.Item name="code" label={t('projects.code')} rules={required}>
        <Input maxLength={50} />
      </Form.Item>
      <Form.Item name="name" label={t('projects.name')} rules={required}>
        <Input maxLength={200} />
      </Form.Item>
      <Form.Item name="customerId" label={t('projects.customer')} extra={t('projects.customerHint')}>
        <Select
          allowClear
          showSearch={{ optionFilterProp: 'label' }}
          loading={customers.isPending}
          options={(customers.data ?? []).map((c) => ({ value: c.id, label: c.name }))}
        />
      </Form.Item>
      {assignsManager && (
        <Form.Item name="managerId" label={t('projects.manager')} extra={t('projects.managerHint')}>
          <Select
            allowClear
            showSearch={{ optionFilterProp: 'label' }}
            loading={members.isPending}
            options={managers.map((m) => ({ value: m.userId, label: `${m.fullName} · ${t(`roles.${m.role}`)}` }))}
          />
        </Form.Item>
      )}
      <Form.Item name="location" label={t('projects.location')}>
        <Input />
      </Form.Item>
      <Form.Item name="country" label={t('projects.country')} extra={t('customers.countryHint')}>
        <Input maxLength={2} style={{ width: 80, textTransform: 'uppercase' }} />
      </Form.Item>
      <Form.Item name="timeZone" label={t('projects.timeZone')} rules={required} extra={t('projects.timeZoneHint')}>
        <Select showSearch options={zones.map((zone) => ({ value: zone, label: zone }))} />
      </Form.Item>
      <Form.Item name="startDate" label={t('projects.startDate')} rules={required}>
        <DatePicker format="DD.MM.YYYY" />
      </Form.Item>
      <Form.Item name="endDate" label={t('projects.endDate')}>
        <DatePicker format="DD.MM.YYYY" />
      </Form.Item>
    </>
  )
}
