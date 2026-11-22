import { App, Button, Divider, Drawer, Flex, Form } from 'antd'
import dayjs, { type Dayjs } from 'dayjs'
import { useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router'
import { errorMessage, applyFieldErrors } from '../../api/formErrors'
import { type Shift, useCreateProject } from '../../api/projects'
import { ProjectFields } from './ProjectFields'
import { ShiftEditor } from './ShiftEditor'
import { SHIFT_PRESETS } from './shiftPresets'

interface FormValues {
  code: string
  name: string
  customerId?: string
  location?: string
  country?: string
  timeZone: string
  startDate: Dayjs
  endDate?: Dayjs
  shifts: Shift[]
}

export function ProjectCreateDrawer({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { t } = useTranslation()
  const { message } = App.useApp()
  const navigate = useNavigate()
  const [form] = Form.useForm<FormValues>()
  const create = useCreateProject()

  useEffect(() => {
    if (!open) return
    form.resetFields()
    form.setFieldsValue({
      timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone,
      startDate: dayjs(),
      shifts: SHIFT_PRESETS['2×12'].map((s) => ({ ...s })),
    })
  }, [open, form])

  const onFinish = (values: FormValues) => {
    create.mutate(
      {
        ...values,
        country: values.country?.toUpperCase() || undefined,
        startDate: values.startDate.format('YYYY-MM-DD'),
        endDate: values.endDate?.format('YYYY-MM-DD'),
      },
      {
        onSuccess: (project) => {
          message.success(t('common.saved'))
          onClose()
          navigate(`/projects/${project.id}`)
        },
        onError: (error) => {
          if (!applyFieldErrors(form, error, t)) message.error(errorMessage(error, t))
        },
      },
    )
  }

  return (
    <Drawer
      open={open}
      onClose={onClose}
      size={560}
      destroyOnHidden
      title={t('projects.new')}
      footer={
        <Flex justify="flex-end" gap={8}>
          <Button onClick={onClose}>{t('common.cancel')}</Button>
          <Button type="primary" loading={create.isPending} onClick={() => form.submit()}>
            {t('common.save')}
          </Button>
        </Flex>
      }
    >
      <Form<FormValues> form={form} layout="vertical" onFinish={onFinish} requiredMark="optional">
        <ProjectFields />
        <Divider titlePlacement="start">{t('projects.shiftSchedule')}</Divider>
        <ShiftEditor form={form} />
      </Form>
    </Drawer>
  )
}
