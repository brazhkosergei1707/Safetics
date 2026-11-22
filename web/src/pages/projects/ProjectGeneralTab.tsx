import { App, Button, Descriptions, Form } from 'antd'
import dayjs, { type Dayjs } from 'dayjs'
import { useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { hasPermission, useSession } from '../../api/auth'
import { ApiError } from '../../api/client'
import { applyFieldErrors, errorMessage } from '../../api/formErrors'
import { type Project, useUpdateProject } from '../../api/projects'
import { ProjectFields } from './ProjectFields'

interface FormValues {
  code: string
  name: string
  customerId?: string
  location?: string
  country?: string
  timeZone: string
  startDate: Dayjs
  endDate?: Dayjs
}

/** Загальні дані проєкту: форма для project.manage, інакше — лише перегляд. */
export function ProjectGeneralTab({ project }: { project: Project }) {
  const { t } = useTranslation()
  const { message } = App.useApp()
  const { data: session } = useSession()
  const [form] = Form.useForm<FormValues>()
  const update = useUpdateProject()
  const canManage = hasPermission(session, 'project.manage')

  useEffect(() => {
    form.setFieldsValue({
      code: project.code,
      name: project.name,
      customerId: project.customerId,
      location: project.location,
      country: project.country,
      timeZone: project.timeZone,
      startDate: dayjs(project.startDate),
      endDate: project.endDate ? dayjs(project.endDate) : undefined,
    })
  }, [project, form])

  if (!canManage) {
    return (
      <Descriptions
        column={1}
        bordered
        size="small"
        style={{ maxWidth: 640 }}
        items={[
          { label: t('projects.customer'), children: project.customerName ?? '—' },
          { label: t('projects.location'), children: [project.location, project.country].filter(Boolean).join(', ') },
          { label: t('projects.timeZone'), children: project.timeZone },
          { label: t('projects.startDate'), children: dayjs(project.startDate).format('DD.MM.YYYY') },
          { label: t('projects.endDate'), children: project.endDate ? dayjs(project.endDate).format('DD.MM.YYYY') : '—' },
        ]}
      />
    )
  }

  const onFinish = (values: FormValues) => {
    update.mutate(
      {
        id: project.id,
        body: {
          ...values,
          customerId: values.customerId ?? undefined,
          country: values.country?.toUpperCase() || undefined,
          startDate: values.startDate.format('YYYY-MM-DD'),
          endDate: values.endDate?.format('YYYY-MM-DD'),
          version: project.version,
        },
      },
      {
        onSuccess: () => message.success(t('common.saved')),
        onError: (error) => {
          if (error instanceof ApiError && error.code === 'stale_version') {
            message.error(t('apiErrors.stale_version'))
          } else if (!applyFieldErrors(form, error, t)) {
            message.error(errorMessage(error, t))
          }
        },
      },
    )
  }

  return (
    <Form<FormValues> form={form} layout="vertical" onFinish={onFinish} requiredMark="optional" style={{ maxWidth: 560 }}>
      <ProjectFields />
      <Button type="primary" htmlType="submit" loading={update.isPending}>
        {t('common.save')}
      </Button>
    </Form>
  )
}
