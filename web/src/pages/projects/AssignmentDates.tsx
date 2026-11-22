import { DatePicker, Form } from 'antd'
import { useTranslation } from 'react-i18next'

/** Поля «з / по» для призначення; «по» порожнє — без дати завершення. */
export function AssignmentDates() {
  const { t } = useTranslation()
  return (
    <>
      <Form.Item name="startDate" label={t('assignments.from')} rules={[{ required: true, message: t('validation.required') }]}>
        <DatePicker format="DD.MM.YYYY" />
      </Form.Item>
      <Form.Item name="endDate" label={t('assignments.to')} extra={t('assignments.toHint')}>
        <DatePicker format="DD.MM.YYYY" allowClear />
      </Form.Item>
    </>
  )
}
