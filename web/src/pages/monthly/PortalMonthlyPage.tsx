import { FileExcelOutlined, PrinterOutlined } from '@ant-design/icons'
import { Alert, App, Button, Drawer, Flex, Form, Input, Modal, Popconfirm, Table, Tag, Typography } from 'antd'
import dayjs from 'dayjs'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { errorMessage } from '../../api/formErrors'
import { type MonthlyReport, usePortalMonthlyDecision, usePortalMonthlyReports } from '../../api/monthly'
import { hasPermission, useSession } from '../../api/auth'
import { MonthlyContentView } from './MonthlyContentView'
import { MONTHLY_STATUS_COLORS, downloadCsv, printReport } from './monthlyExport'

/** Портал: місячні звіти, надіслані підрядником. Менеджер замовника затверджує або повертає. */
export function PortalMonthlyPage() {
  const { t } = useTranslation()
  const { message } = App.useApp()
  const { data: session } = useSession()
  const list = usePortalMonthlyReports()
  const decide = usePortalMonthlyDecision()
  const [openedId, setOpenedId] = useState<string>()
  const [returning, setReturning] = useState(false)
  const [form] = Form.useForm<{ comment: string }>()
  const canDecide = hasPermission(session, 'monthly_report.approve')
  const opened = list.data?.find((r) => r.id === openedId)
  const hours = (minutes: number) => `${Math.round((minutes / 60) * 100) / 100} ${t('projects.hoursShort')}`

  const run = (approve: boolean, comment?: string) =>
    opened &&
    decide.mutate(
      { id: opened.id, approve, comment },
      {
        onSuccess: () => {
          message.success(t(approve ? 'monthly.approvedMessage' : 'monthly.returnedMessage'))
          setReturning(false)
        },
        onError: (e) => message.error(errorMessage(e, t)),
      },
    )

  return (
    <>
      <Typography.Title level={2}>{t('monthly.portalTitle')}</Typography.Title>
      <Typography.Paragraph type="secondary">{t('monthly.portalHint')}</Typography.Paragraph>
      <Table<MonthlyReport>
        rowKey="id"
        loading={list.isPending}
        dataSource={list.data ?? []}
        onRow={(r) => ({ onClick: () => setOpenedId(r.id), style: { cursor: 'pointer' } })}
        columns={[
          { title: t('monthly.month'), render: (_, r) => dayjs(r.month).format('MM.YYYY'), width: 100 },
          { title: t('review.project'), render: (_, r) => `${r.projectCode} · ${r.projectName}` },
          { title: 'v', dataIndex: 'version', width: 50 },
          { title: t('reports.meters'), render: (_, r) => `${r.content.totals.meters} m`, align: 'right' },
          { title: t('reports.billable'), render: (_, r) => hours(r.content.totals.billableMinutes), align: 'right' },
          { title: t('monthly.sentAt'), render: (_, r) => (r.sentAt ? dayjs(r.sentAt).format('DD.MM.YYYY') : '') },
          {
            title: t('projects.statusLabel'),
            render: (_, r) => <Tag color={MONTHLY_STATUS_COLORS[r.status]}>{t(`monthly.status.${r.status}`)}</Tag>,
          },
        ]}
      />
      <Drawer
        open={!!opened}
        onClose={() => setOpenedId(undefined)}
        size={1000}
        destroyOnHidden
        title={opened && `${opened.projectCode} · ${dayjs(opened.month).format('MM.YYYY')} · v${opened.version}`}
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
        footer={
          opened?.status === 'sent' &&
          canDecide && (
            <Flex justify="flex-end" gap={8}>
              <Button danger onClick={() => setReturning(true)}>
                {t('review.return')}
              </Button>
              <Popconfirm title={t('monthly.approveConfirm')} onConfirm={() => run(true)}>
                <Button type="primary" loading={decide.isPending}>
                  {t('monthly.approve')}
                </Button>
              </Popconfirm>
            </Flex>
          )
        }
      >
        {opened && (
          <>
            {opened.status === 'approved' && (
              <Alert type="success" showIcon style={{ marginBottom: 12 }}
                title={t('monthly.approvedBy', { name: opened.decidedByName ?? '', at: dayjs(opened.decidedAt).format('DD.MM.YYYY') })} />
            )}
            {opened.decisionComment && <Alert type="error" showIcon style={{ marginBottom: 12 }} title={opened.decisionComment} />}
            <MonthlyContentView content={opened.content} />
          </>
        )}
      </Drawer>
      <Modal
        open={returning}
        title={t('review.return')}
        onCancel={() => setReturning(false)}
        onOk={() => form.submit()}
        okText={t('review.return')}
        okButtonProps={{ danger: true }}
        cancelText={t('common.cancel')}
        confirmLoading={decide.isPending}
        destroyOnHidden
      >
        <Form form={form} layout="vertical" preserve={false} onFinish={(v) => run(false, v.comment)}>
          <Form.Item name="comment" label={t('review.comment')} extra={t('monthly.returnHint')}
            rules={[{ required: true, whitespace: true, message: t('validation.required') }]}>
            <Input.TextArea rows={3} />
          </Form.Item>
        </Form>
      </Modal>
    </>
  )
}
