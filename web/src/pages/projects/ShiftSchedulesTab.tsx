import { PlusOutlined } from '@ant-design/icons'
import { App, Button, DatePicker, Flex, Form, Modal, Popconfirm, Table, Tag, Typography } from 'antd'
import dayjs, { type Dayjs } from 'dayjs'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { hasPermission, useSession } from '../../api/auth'
import { applyFieldErrors, errorMessage } from '../../api/formErrors'
import {
  type Project,
  type Shift,
  type ShiftSchedule,
  useDeleteShiftSchedule,
  useSaveShiftSchedule,
  useShiftSchedules,
} from '../../api/projects'
import { ShiftEditor } from './ShiftEditor'

interface FormValues {
  validFrom: Dayjs
  shifts: Shift[]
}

/** Версії графіка змін. Нова версія — з майбутньої дати; поточну й минулі змінити не можна. */
export function ShiftSchedulesTab({ project }: { project: Project }) {
  const { t } = useTranslation()
  const { message } = App.useApp()
  const { data: session } = useSession()
  const schedules = useShiftSchedules(project.id)
  const save = useSaveShiftSchedule(project.id)
  const remove = useDeleteShiftSchedule(project.id)
  const [form] = Form.useForm<FormValues>()
  const [open, setOpen] = useState(false)
  const canManage = hasPermission(session, 'project.manage') && project.status === 'active'

  const openEditor = (schedule?: ShiftSchedule) => {
    const base = schedule ?? schedules.data?.find((s) => s.current)
    form.resetFields()
    form.setFieldsValue({
      validFrom: schedule ? dayjs(schedule.validFrom) : dayjs().add(1, 'day'),
      shifts: (base?.shifts ?? []).map((s) => ({ ...s })),
    })
    setOpen(true)
  }

  const onFinish = (values: FormValues) => {
    save.mutate(
      { validFrom: values.validFrom.format('YYYY-MM-DD'), shifts: values.shifts },
      {
        onSuccess: () => {
          message.success(t('common.saved'))
          setOpen(false)
        },
        onError: (error) => {
          if (!applyFieldErrors(form, error, t)) message.error(errorMessage(error, t))
        },
      },
    )
  }

  return (
    <>
      <Flex justify="space-between" align="center" style={{ marginBottom: 12 }}>
        <Typography.Text type="secondary">
          {t('projects.scheduleHint', { timeZone: project.timeZone })}
        </Typography.Text>
        {canManage && (
          <Button icon={<PlusOutlined />} onClick={() => openEditor()}>
            {t('projects.newScheduleVersion')}
          </Button>
        )}
      </Flex>

      <Table<ShiftSchedule>
        rowKey="id"
        size="middle"
        pagination={false}
        loading={schedules.isPending}
        dataSource={schedules.data ?? []}
        columns={[
          {
            title: t('projects.validFrom'),
            dataIndex: 'validFrom',
            render: (value: string, s) => (
              <Flex gap={8} align="center">
                {dayjs(value).format('DD.MM.YYYY')}
                {s.current && <Tag color="green">{t('projects.current')}</Tag>}
                {s.editable && <Tag color="blue">{t('projects.planned')}</Tag>}
              </Flex>
            ),
          },
          {
            title: t('projects.shifts'),
            key: 'shifts',
            render: (_, s) =>
              s.shifts.map((shift) => `${shift.name} ${shift.start} · ${shift.durationMinutes / 60} ${t('projects.hoursShort')}`).join('; '),
          },
          {
            key: 'actions',
            align: 'right',
            render: (_, s) =>
              canManage && s.editable ? (
                <Flex gap={4} justify="flex-end">
                  <Button type="link" size="small" onClick={() => openEditor(s)}>
                    {t('common.edit')}
                  </Button>
                  <Popconfirm
                    title={t('projects.deleteScheduleConfirm')}
                    onConfirm={() => remove.mutate(s.id, { onError: (error) => message.error(errorMessage(error, t)) })}
                  >
                    <Button type="link" size="small" danger>
                      {t('common.delete')}
                    </Button>
                  </Popconfirm>
                </Flex>
              ) : null,
          },
        ]}
      />

      <Modal
        open={open}
        title={t('projects.newScheduleVersion')}
        width={640}
        onCancel={() => setOpen(false)}
        onOk={() => form.submit()}
        okText={t('common.save')}
        cancelText={t('common.cancel')}
        confirmLoading={save.isPending}
        destroyOnHidden
      >
        <Form<FormValues> form={form} layout="vertical" onFinish={onFinish}>
          <Form.Item
            name="validFrom"
            label={t('projects.validFrom')}
            rules={[{ required: true, message: t('validation.required') }]}
          >
            <DatePicker format="DD.MM.YYYY" disabledDate={(d) => !d.isAfter(dayjs(), 'day')} />
          </Form.Item>
          <ShiftEditor form={form} />
        </Form>
      </Modal>
    </>
  )
}
