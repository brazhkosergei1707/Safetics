import { FileExcelOutlined, PlusOutlined, PrinterOutlined } from '@ant-design/icons'
import {
  Alert,
  App,
  Button,
  DatePicker,
  Drawer,
  Flex,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Select,
  Spin,
  Table,
  Tag,
  Typography,
} from 'antd'
import dayjs, { type Dayjs } from 'dayjs'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { applyFieldErrors, errorMessage } from '../../api/formErrors'
import { type AdjustmentRequest, type MonthlyReport, useMonthAction, useProjectMonth } from '../../api/monthly'
import type { Project } from '../../api/projects'
import { ShiftReportDrawer } from '../shifts/ShiftReportDrawer'
import { MonthlyContentView } from './MonthlyContentView'
import { MONTHLY_STATUS_COLORS, downloadCsv, printReport } from './monthlyExport'

/**
 * Місячний звіт проєкту (підрядник): живий перегляд місяця, включення неперевірених рапортів,
 * коригування, генерація версій, надсилання замовнику, CSV і друк.
 */
export function ProjectMonthlyTab({ project }: { project: Project }) {
  const { t } = useTranslation()
  const { message } = App.useApp()
  const [month, setMonth] = useState<Dayjs>(dayjs().subtract(1, 'month').startOf('month'))
  const key = month.format('YYYY-MM')
  const view = useProjectMonth(project.id, key)
  const action = useMonthAction(project.id, key)
  const [adjusting, setAdjusting] = useState(false)
  const [opened, setOpened] = useState<MonthlyReport>()
  const [shiftId, setShiftId] = useState<string>()
  const data = view.data
  const latest = data?.versions[0]
  const editable = !!data?.canManage && !data.closed
  const onError = (e: unknown) => message.error(errorMessage(e, t))

  return (
    <>
      <Flex justify="space-between" align="center" gap={12} wrap style={{ marginBottom: 16 }}>
        <Flex gap={12} align="center">
          <DatePicker picker="month" allowClear={false} value={month} format="MM.YYYY" onChange={(v) => v && setMonth(v.startOf('month'))} />
          {data?.closed && <Tag color="green">{t('monthly.closed')}</Tag>}
        </Flex>
        {editable && (
          <Flex gap={8}>
            <Button icon={<PlusOutlined />} onClick={() => setAdjusting(true)}>
              {t('monthly.addAdjustment')}
            </Button>
            <Popconfirm
              title={t('monthly.generateConfirm')}
              onConfirm={() =>
                action.mutate(
                  { kind: 'generate' },
                  { onSuccess: (r) => { message.success(t('monthly.generated')); setOpened(r as MonthlyReport) }, onError },
                )
              }
              disabled={latest?.status === 'sent'}
            >
              <Button type="primary" disabled={latest?.status === 'sent'} loading={action.isPending && action.variables?.kind === 'generate'}>
                {t('monthly.generate')}
              </Button>
            </Popconfirm>
          </Flex>
        )}
      </Flex>
      {latest?.status === 'sent' && <Alert type="info" showIcon style={{ marginBottom: 12 }} title={t('monthly.waitingClient')} />}
      {latest?.status === 'returned' && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 12 }}
          title={t('monthly.returnedBy', { name: latest.decidedByName ?? '' })}
          description={latest.decisionComment}
        />
      )}

      {!data ? (
        <Spin />
      ) : (
        <>
          {data.versions.length > 0 && (
            <>
              <Typography.Title level={5}>{t('monthly.versions')}</Typography.Title>
              <Table<MonthlyReport>
                size="small"
                pagination={false}
                rowKey="id"
                dataSource={data.versions}
                style={{ marginBottom: 24 }}
                columns={[
                  { title: 'v', dataIndex: 'version', width: 50 },
                  {
                    title: t('projects.statusLabel'),
                    render: (_, r) => <Tag color={MONTHLY_STATUS_COLORS[r.status]}>{t(`monthly.status.${r.status}`)}</Tag>,
                  },
                  { title: t('monthly.generatedAt'), render: (_, r) => `${dayjs(r.generatedAt).format('DD.MM HH:mm')} · ${r.generatedByName ?? ''}` },
                  { title: t('monthly.sentAt'), render: (_, r) => (r.sentAt ? dayjs(r.sentAt).format('DD.MM HH:mm') : '') },
                  { title: t('reports.meters'), render: (_, r) => `${r.content.totals.meters} m`, align: 'right' },
                  {
                    key: 'actions',
                    render: (_, r) => (
                      <Flex gap={4} justify="flex-end">
                        <Button size="small" type="link" onClick={() => setOpened(r)}>
                          {t('monthly.open')}
                        </Button>
                        {editable && r.status === 'draft' && (
                          <Popconfirm
                            title={t('monthly.sendConfirm')}
                            onConfirm={() =>
                              action.mutate({ kind: 'send', reportId: r.id }, { onSuccess: () => message.success(t('monthly.sentMessage')), onError })
                            }
                          >
                            <Button size="small" type="link">
                              {t('monthly.send')}
                            </Button>
                          </Popconfirm>
                        )}
                      </Flex>
                    ),
                  },
                ]}
              />
            </>
          )}
          <Typography.Title level={5}>{t('monthly.preview')}</Typography.Title>
          <Typography.Paragraph type="secondary">{t('monthly.previewHint')}</Typography.Paragraph>
          <MonthlyContentView
            content={data.preview}
            onOpenShift={setShiftId}
            onInclude={editable ? (id, included) => action.mutate({ kind: 'include', shiftId: id, included }, { onError }) : undefined}
            onDeleteAdjustment={editable ? (id) => action.mutate({ kind: 'deleteAdjustment', adjustmentId: id }, { onError }) : undefined}
          />
        </>
      )}

      <Drawer
        open={!!opened}
        onClose={() => setOpened(undefined)}
        size={1000}
        destroyOnHidden
        title={
          opened && (
            <Flex gap={8} align="center">
              {t('monthly.title')} {opened.month.slice(0, 7)} · v{opened.version}
              <Tag color={MONTHLY_STATUS_COLORS[opened.status]}>{t(`monthly.status.${opened.status}`)}</Tag>
            </Flex>
          )
        }
        extra={
          opened && (
            <Flex gap={8}>
              <Button icon={<FileExcelOutlined />} onClick={() => downloadCsv(opened, t)}>
                CSV
              </Button>
              <Button icon={<PrinterOutlined />} onClick={() => printReport(opened, t)}>
                {t('monthly.print')}
              </Button>
            </Flex>
          )
        }
      >
        {opened && (
          <>
            {opened.decisionComment && <Alert type="error" showIcon style={{ marginBottom: 12 }} title={opened.decisionComment} />}
            <MonthlyContentView content={opened.content} />
          </>
        )}
      </Drawer>

      {data && adjusting && (
        <AdjustmentModal
          assets={data.preview.assets.map((a) => ({ value: a.assetId, label: a.assetCode }))}
          shifts={data.correctableShifts.map((s) => ({ value: s.shiftId, label: s.label }))}
          loading={action.isPending}
          onCancel={() => setAdjusting(false)}
          onSubmit={(body, form) =>
            action.mutate(
              { kind: 'adjust', body },
              {
                onSuccess: () => {
                  message.success(t('common.saved'))
                  setAdjusting(false)
                },
                onError: (e) => {
                  if (!applyFieldErrors(form, e, t)) onError(e)
                },
              },
            )
          }
        />
      )}
      <ShiftReportDrawer project={project} shiftId={shiftId} onClose={() => setShiftId(undefined)} />
    </>
  )
}

type AdjustmentForm = ReturnType<typeof Form.useForm<AdjustmentRequest>>[0]

function AdjustmentModal({ assets, shifts, loading, onCancel, onSubmit }: {
  assets: { value: string; label: string }[]
  shifts: { value: string; label: string }[]
  loading: boolean
  onCancel: () => void
  onSubmit: (body: AdjustmentRequest, form: AdjustmentForm) => void
}) {
  const { t } = useTranslation()
  const [form] = Form.useForm<AdjustmentRequest>()
  const kind = Form.useWatch('kind', form)
  const required = [{ required: true, message: t('validation.required') }]
  return (
    <Modal
      open
      title={t('monthly.addAdjustment')}
      onCancel={onCancel}
      onOk={() => form.submit()}
      okText={t('common.save')}
      cancelText={t('common.cancel')}
      confirmLoading={loading}
    >
      <Typography.Paragraph type="secondary">{t('monthly.adjustmentHint')}</Typography.Paragraph>
      <Form<AdjustmentRequest> form={form} layout="vertical" initialValues={{ kind: 'meters' }} onFinish={(v) => onSubmit(v, form)}>
        <Form.Item name="kind" label={t('monthly.kind')} rules={required}>
          <Select options={(['meters', 'hours', 'quantity'] as const).map((k) => ({ value: k, label: t(`monthly.kinds.${k}`) }))} />
        </Form.Item>
        <Flex gap={12}>
          <Form.Item name="amount" label={t('monthly.amount')} rules={required} extra={t('monthly.amountHint')} style={{ flex: 1 }}>
            <InputNumber step={0.1} decimalSeparator="," style={{ width: '100%' }} />
          </Form.Item>
          {kind === 'quantity' && (
            <Form.Item name="unit" label={t('monthly.unit')} rules={required} style={{ width: 140 }}>
              <Input />
            </Form.Item>
          )}
        </Flex>
        <Form.Item name="assetId" label={t('review.rig')}>
          <Select allowClear options={assets} />
        </Form.Item>
        {shifts.length > 0 && (
          <Form.Item name="correctsShiftId" label={t('monthly.correctsShift')} extra={t('monthly.correctsShiftHint')}>
            <Select allowClear showSearch={{ optionFilterProp: 'label' }} options={shifts} />
          </Form.Item>
        )}
        <Form.Item name="comment" label={t('review.comment')} rules={[{ required: true, whitespace: true, message: t('validation.required') }]}>
          <Input.TextArea rows={2} />
        </Form.Item>
      </Form>
    </Modal>
  )
}
