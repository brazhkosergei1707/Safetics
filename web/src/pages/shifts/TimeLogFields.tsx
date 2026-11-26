import { DeleteOutlined, PlusOutlined } from '@ant-design/icons'
import { AutoComplete, Button, Flex, Form, type FormInstance, Input, InputNumber, Select, TimePicker, Typography } from 'antd'
import type { Dayjs } from 'dayjs'
import { useTranslation } from 'react-i18next'
import type { ActivityCode } from '../../api/reportCatalogs'
import type { Schemas } from '../../api/client'
import { localized } from '../../i18n/localized'
import type { TimeRow } from './timeLog'

const CORE_SIZES = ['AQ', 'BQ', 'NQ', 'HQ', 'PQ', 'RC'].map((value) => ({ value }))

type TaskOption = Schemas['ReportTaskOption']
type IntervalStart = Schemas['IntervalStart']

interface Props {
  form: FormInstance
  codes: ActivityCode[]
  taskOptions: TaskOption[]
  /** Свердловини попереднього рапорту з останнім «до» — задача й глибина за замовчуванням */
  prefill: IntervalStart[]
  editable: boolean
  /** Замовник: задачу обирає лише в рядках буріння (як свердловину), інші задачі не бачить */
  client?: boolean
  firstFrom: () => Dayjs
}

/**
 * Журнал часу з інтервалами буріння: код «Буріння» відкриває під рядком інтервал (глибина від / до,
 * типорозмір, керн, вихід). Задача буріння й «від» підставляються з попередніх рядків або зміни.
 */
export function TimeLogFields({ form, codes, taskOptions, prefill, editable, client, firstFrom }: Props) {
  const { t, i18n } = useTranslation()
  const lang = i18n.language
  const rows = Form.useWatch('times', form) as TimeRow[] | undefined
  const drilling = new Set(codes.filter((c) => c.drilling).map((c) => c.id))
  const serviceOf = new Map(taskOptions.map((o) => [o.id, o.serviceId ?? undefined]))
  const holeTasks = taskOptions.filter((o) => o.serviceId)
  const codeOptions = codes.map((c) => ({ value: c.id, label: `${c.code} · ${localized(c.name, lang)}` }))
  const allTaskOptions = taskOptions.map((o) => ({ value: o.id, label: `#${o.number} ${o.title}` }))
  const holeOptions = holeTasks.map((o) => ({ value: o.id, label: client ? (o.serviceCode ?? o.title) : `#${o.number} ${o.title}` }))
  const required = [{ required: true, message: t('validation.required') }]

  const current = (): TimeRow[] => (form.getFieldValue('times') as TimeRow[] | undefined) ?? []

  /** Задача буріння за замовчуванням: з попереднього рядка буріння, з попередньої зміни, перша відкрита. */
  const defaultDrillTask = (index: number) => {
    const list = current()
    for (let i = index - 1; i >= 0; i--) {
      const row = list[i]
      if (row?.activityCodeId && drilling.has(row.activityCodeId) && row.taskId) return row.taskId
    }
    const fromPrevious = prefill.find((p) => p.taskId && serviceOf.has(p.taskId))?.taskId
    if (fromPrevious) return fromPrevious
    return (holeTasks.find((o) => o.statusCategory === 'active') ?? holeTasks[0])?.id
  }

  /** «Від» для свердловини задачі: останнє «до» вище в рапорті або з попередніх змін. */
  const nextDepth = (taskId: string | undefined, index: number) => {
    const service = taskId ? serviceOf.get(taskId) : undefined
    if (!service) return undefined
    const list = current()
    for (let i = index - 1; i >= 0; i--) {
      const row = list[i]
      if (row?.taskId && serviceOf.get(row.taskId) === service && row.interval?.depthTo != null) return row.interval.depthTo
    }
    return prefill.find((p) => p.serviceId === service)?.depthFrom
  }

  const fillDepth = (index: number, taskId: string | undefined) => {
    if (current()[index]?.interval?.depthFrom != null) return
    const depth = nextDepth(taskId, index)
    if (depth != null) form.setFieldValue(['times', index, 'interval', 'depthFrom'], depth)
  }

  const onCodeChange = (index: number, codeId: string) => {
    if (!drilling.has(codeId)) return
    let task = current()[index]?.taskId
    if (!task || !serviceOf.get(task)) {
      task = defaultDrillTask(index)
      form.setFieldValue(['times', index, 'taskId'], task)
    }
    fillDepth(index, task)
  }

  return (
    <Form.List name="times">
      {(fields, { add, remove }) => (
        <>
          {fields.map((field) => {
            const row = rows?.[field.name]
            const isDrilling = !!row?.activityCodeId && drilling.has(row.activityCodeId)
            const interval = row?.interval
            const meters =
              interval?.depthFrom != null && interval?.depthTo != null ? interval.depthTo - interval.depthFrom : undefined
            return (
              <div key={field.key} data-testid={`time-row-${field.name}`} style={{ marginBottom: isDrilling ? 4 : 0 }}>
                <Flex gap={8} align="start" wrap>
                  <Form.Item name={[field.name, 'from']} rules={required} style={{ marginBottom: 8 }}>
                    <TimePicker format="HH:mm" minuteStep={5} needConfirm={false} placeholder={t('reports.from')} style={{ width: 100 }} />
                  </Form.Item>
                  <Form.Item name={[field.name, 'to']} rules={required} style={{ marginBottom: 8 }}>
                    <TimePicker format="HH:mm" minuteStep={5} needConfirm={false} placeholder={t('reports.to')} style={{ width: 100 }} />
                  </Form.Item>
                  <Form.Item name={[field.name, 'activityCodeId']} rules={required} style={{ marginBottom: 8, width: 260 }}>
                    <Select
                      showSearch={{ optionFilterProp: 'label' }}
                      placeholder={t('reports.activityCode')}
                      options={codeOptions}
                      onChange={(value: string) => onCodeChange(field.name, value)}
                    />
                  </Form.Item>
                  {client && !isDrilling ? (
                    <Form.Item name={[field.name, 'taskId']} hidden>
                      <Input />
                    </Form.Item>
                  ) : (
                    <Form.Item
                      name={[field.name, 'taskId']}
                      rules={isDrilling ? required : undefined}
                      style={{ marginBottom: 8, width: client ? 160 : 260 }}
                    >
                      <Select
                        allowClear={!isDrilling}
                        showSearch={{ optionFilterProp: 'label' }}
                        placeholder={isDrilling ? t('reports.hole') : t('reports.task')}
                        options={isDrilling ? holeOptions : allTaskOptions}
                        onChange={(value: string | undefined) => isDrilling && fillDepth(field.name, value)}
                      />
                    </Form.Item>
                  )}
                  <Form.Item name={[field.name, 'comment']} style={{ marginBottom: 8, flex: 1, minWidth: 140 }}>
                    <Input placeholder={t('reports.comment')} />
                  </Form.Item>
                  {editable && <Button icon={<DeleteOutlined />} onClick={() => remove(field.name)} />}
                </Flex>
                {isDrilling && (
                  <Flex gap={8} align="start" wrap style={{ paddingInlineStart: 24 }}>
                    <Typography.Text type="secondary" style={{ lineHeight: '32px' }}>
                      └ {t('reports.interval')}
                    </Typography.Text>
                    <Form.Item name={[field.name, 'interval', 'depthFrom']} style={{ marginBottom: 8 }}>
                      <InputNumber min={0} step={0.1} placeholder={t('reports.depthFrom')} suffix="m" decimalSeparator="," style={{ width: 120 }} />
                    </Form.Item>
                    <Form.Item name={[field.name, 'interval', 'depthTo']} style={{ marginBottom: 8 }}>
                      <InputNumber min={0} step={0.1} placeholder={t('reports.depthTo')} suffix="m" decimalSeparator="," style={{ width: 120 }} />
                    </Form.Item>
                    <Form.Item name={[field.name, 'interval', 'coreSize']} style={{ marginBottom: 8, width: 90 }}>
                      <AutoComplete options={CORE_SIZES} placeholder={t('reports.coreSize')} />
                    </Form.Item>
                    <Form.Item name={[field.name, 'interval', 'coreLength']} style={{ marginBottom: 8 }}>
                      <InputNumber min={0} step={0.1} placeholder={t('reports.coreLength')} suffix="m" decimalSeparator="," style={{ width: 130 }} />
                    </Form.Item>
                    <Typography.Text type="secondary" style={{ lineHeight: '32px', minWidth: 110 }}>
                      {meters != null && meters > 0
                        ? `${Math.round(meters * 100) / 100} m` +
                          (interval?.coreLength != null ? ` · ${Math.round((interval.coreLength / meters) * 1000) / 10}%` : '')
                        : ''}
                    </Typography.Text>
                  </Flex>
                )}
              </div>
            )
          })}
          {editable && (
            <Button
              type="dashed"
              icon={<PlusOutlined />}
              onClick={() => {
                const list = current()
                const last = list[list.length - 1]
                const index = list.length
                add({ from: last?.to ?? firstFrom(), activityCodeId: last?.activityCodeId, taskId: last?.taskId })
                if (last?.activityCodeId && drilling.has(last.activityCodeId)) {
                  if (last.interval?.coreSize) form.setFieldValue(['times', index, 'interval', 'coreSize'], last.interval.coreSize)
                  fillDepth(index, last.taskId)
                }
              }}
            >
              {t('reports.addTime')}
            </Button>
          )}
        </>
      )}
    </Form.List>
  )
}
