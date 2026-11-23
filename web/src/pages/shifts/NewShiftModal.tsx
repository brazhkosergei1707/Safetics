import { App, Form, Modal, Select, Typography } from 'antd'
import dayjs from 'dayjs'
import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { applyFieldErrors, errorMessage, leadRename } from '../../api/formErrors'
import { type Project, useProjectPersonnel } from '../../api/projects'
import { type WorkShift, useCreateShift } from '../../api/shifts'
import { localized } from '../../i18n/localized'

export interface NewShiftTarget {
  assetId: string
  assetCode: string
  date: string
  shiftCode: string
  shiftName: string
}

interface Values {
  leadId?: string
  memberIds?: string[]
}

interface Props {
  project: Project
  target?: NewShiftTarget
  onClose: () => void
  onCreated: (shift: WorkShift) => void
}

/**
 * Зміна вручну: можна одразу вказати відповідального (з входом) і решту бригади — тоді задачі
 * зміни (чек-лист, рапорт) одразу на ньому. Нічого не вказано — бригада з попередньої зміни.
 */
export function NewShiftModal({ project, target, onClose, onCreated }: Props) {
  const { t, i18n } = useTranslation()
  const lang = i18n.language
  const { message } = App.useApp()
  const [form] = Form.useForm<Values>()
  const create = useCreateShift(project.id)
  const personnel = useProjectPersonnel(project.id)
  const leadId = Form.useWatch('leadId', form)
  const memberIds = Form.useWatch('memberIds', form)

  // Лише ті, хто в день зміни призначений на проєкт
  const people = useMemo(() => {
    const seen = new Map<string, { label: string; hasLogin: boolean }>()
    if (target) {
      for (const a of personnel.data ?? []) {
        if (a.startDate <= target.date && (!a.endDate || a.endDate >= target.date)) {
          seen.set(a.personnelId, { label: `${a.fullName} · ${localized(a.positionName, lang)}`, hasLogin: a.hasLogin })
        }
      }
    }
    return [...seen.entries()].map(([value, p]) => ({ value, ...p }))
  }, [personnel.data, target, lang])

  const onFinish = (v: Values) => {
    if (!target) return
    const members = (v.memberIds ?? []).filter((id) => id !== v.leadId)
    const crew = v.leadId
      ? [{ personnelId: v.leadId, lead: true }, ...members.map((personnelId) => ({ personnelId, lead: false }))]
      : undefined
    create.mutate(
      { assetId: target.assetId, date: target.date, shiftCode: target.shiftCode, crew },
      {
        onSuccess: (shift) => {
          onCreated(shift)
          onClose()
        },
        onError: (error) => {
          if (!applyFieldErrors(form, error, t, leadRename(error))) message.error(errorMessage(error, t))
        },
      },
    )
  }

  return (
    <Modal
      open={!!target}
      title={target ? `${t('shifts.newShift')}: ${target.assetCode} · ${dayjs(target.date).format('DD.MM.YYYY')} · ${target.shiftName}` : ''}
      onCancel={onClose}
      onOk={() => form.submit()}
      okText={t('common.save')}
      cancelText={t('common.cancel')}
      confirmLoading={create.isPending}
      destroyOnHidden
    >
      <Typography.Paragraph type="secondary">{t('shifts.newShiftHint')}</Typography.Paragraph>
      <Form<Values> form={form} layout="vertical" onFinish={onFinish} preserve={false}>
        <Form.Item
          name="leadId"
          label={t('shifts.lead')}
          extra={t('shifts.leadHint')}
          rules={[{ required: (memberIds ?? []).length > 0, message: t('validation.required') }]}
        >
          <Select
            allowClear
            showSearch={{ optionFilterProp: 'label' }}
            loading={personnel.isPending}
            placeholder={t('shifts.leadPlaceholder')}
            options={people.map((p) => ({
              value: p.value,
              label: p.label,
              disabled: !p.hasLogin,
              title: p.hasLogin ? undefined : t('shifts.leadNoLogin'),
            }))}
          />
        </Form.Item>
        <Form.Item name="memberIds" label={t('shifts.crewMembers')}>
          <Select
            mode="multiple"
            allowClear
            showSearch={{ optionFilterProp: 'label' }}
            options={people.filter((p) => p.value !== leadId).map((p) => ({ value: p.value, label: p.label }))}
          />
        </Form.Item>
      </Form>
    </Modal>
  )
}
