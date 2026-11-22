import { App, Button, Drawer, Flex, Form, Input, List, Popconfirm, Segmented, Spin, Tag, Typography } from 'antd'
import dayjs from 'dayjs'
import { useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { applyFieldErrors, errorMessage } from '../../api/formErrors'
import { type ChecklistAnswer, useShiftChecklist, useSubmitChecklist } from '../../api/shiftChecklists'
import { localized } from '../../i18n/localized'

type Result = ChecklistAnswer['result']

interface FormValues {
  items: Record<string, { result?: Result; comment?: string }>
  comment?: string
}

interface Props {
  shiftId?: string
  title?: string
  onClose: () => void
  onOpenTask?: (id: string) => void
}

/**
 * Чек-лист зміни: кожен пункт — пройдено / не пройдено / н/з, для «не пройдено» — коментар.
 * Не пройдено → задача «Сервіс» (критичний пункт — ще й алерт менеджеру). Нічого не блокує.
 */
export function ShiftChecklistDrawer({ shiftId, title, onClose, onOpenTask }: Props) {
  const { t, i18n } = useTranslation()
  const lang = i18n.language
  const { message } = App.useApp()
  const [form] = Form.useForm<FormValues>()
  const checklist = useShiftChecklist(shiftId)
  const submit = useSubmitChecklist()
  const data = checklist.data
  const values = Form.useWatch([], form) as FormValues | undefined
  const editable = !!data?.canEdit

  useEffect(() => {
    if (!data) return
    form.setFieldsValue({
      comment: data.comment ?? undefined,
      items: Object.fromEntries(
        data.items.map((i) => [i.key, { result: (i.result ?? undefined) as Result | undefined, comment: i.comment ?? undefined }]),
      ),
    })
  }, [data, form])

  const onFinish = (v: FormValues) => {
    if (!shiftId || !data) return
    submit.mutate(
      {
        shiftId,
        body: {
          comment: v.comment,
          answers: data.items
            .filter((i) => v.items?.[i.key]?.result)
            .map((i) => ({ key: i.key, result: v.items[i.key].result!, comment: v.items[i.key].comment })),
        },
      },
      {
        onSuccess: (r) => message.success(t(r.status === 'passed' ? 'checklists.passedMessage' : 'checklists.failedMessage')),
        onError: (error) => {
          if (!applyFieldErrors(form, error, t)) message.error(errorMessage(error, t))
        },
      },
    )
  }

  const allAnswered = !!data && data.items.every((i) => values?.items?.[i.key]?.result)
  const statusColor = data?.status === 'passed' ? 'green' : data?.status === 'failed' ? 'red' : undefined

  return (
    <Drawer
      open={!!shiftId}
      onClose={onClose}
      size={720}
      destroyOnHidden
      title={
        <Flex gap={8} align="center">
          {t('checklists.title')}
          {title && <Typography.Text type="secondary">{title}</Typography.Text>}
          {data && <Tag color={statusColor}>{t(`checklists.status.${data.status}`)}</Tag>}
        </Flex>
      }
      footer={
        editable && (
          <Flex justify="flex-end" gap={8}>
            <Button onClick={onClose}>{t('common.cancel')}</Button>
            <Popconfirm title={t('checklists.submitConfirm')} onConfirm={() => form.submit()} disabled={!allAnswered}>
              <Button type="primary" disabled={!allAnswered} loading={submit.isPending}>
                {t('checklists.submit')}
              </Button>
            </Popconfirm>
          </Flex>
        )
      }
    >
      {!data ? (
        <Spin />
      ) : (
        <Form<FormValues> form={form} onFinish={onFinish} disabled={!editable} layout="vertical">
          {data.submittedAt && (
            <Typography.Paragraph type="secondary">
              {t('checklists.submittedBy', { name: data.submittedByName ?? '', at: dayjs(data.submittedAt).format('DD.MM.YYYY HH:mm') })}
            </Typography.Paragraph>
          )}
          {data.serviceTaskId && (
            <Typography.Paragraph>
              {t('checklists.serviceCreated')}{' '}
              <Typography.Link onClick={() => onOpenTask?.(data.serviceTaskId!)}>#{data.serviceTaskNumber}</Typography.Link>
            </Typography.Paragraph>
          )}
          {data.items.length === 0 && <Typography.Paragraph type="secondary">{t('checklists.noItems')}</Typography.Paragraph>}
          <List
            dataSource={data.items}
            renderItem={(item) => {
              const failed = values?.items?.[item.key]?.result === 'fail'
              return (
                <List.Item style={{ display: 'block' }}>
                  <Flex justify="space-between" align="center" gap={12} wrap>
                    <Flex gap={8} align="center">
                      <Typography.Text>{localized(item.label, lang)}</Typography.Text>
                      {item.critical && <Tag color="red">{t('checklists.critical')}</Tag>}
                    </Flex>
                    <Form.Item name={['items', item.key, 'result']} style={{ marginBottom: 0 }}>
                      <Segmented<Result>
                        options={[
                          { value: 'pass', label: t('checklists.results.pass') },
                          { value: 'fail', label: t('checklists.results.fail') },
                          { value: 'na', label: t('checklists.results.na') },
                        ]}
                      />
                    </Form.Item>
                  </Flex>
                  {(failed || values?.items?.[item.key]?.comment) && (
                    <Form.Item
                      name={['items', item.key, 'comment']}
                      style={{ marginTop: 8, marginBottom: 0 }}
                      rules={failed ? [{ required: true, message: t('checklists.commentRequired') }] : []}
                    >
                      <Input placeholder={t('checklists.comment')} />
                    </Form.Item>
                  )}
                </List.Item>
              )
            }}
          />
          <Form.Item name="comment" label={t('checklists.generalComment')} style={{ marginTop: 12 }}>
            <Input.TextArea rows={2} />
          </Form.Item>
        </Form>
      )}
    </Drawer>
  )
}
