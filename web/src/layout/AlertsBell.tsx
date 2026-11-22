import { BellOutlined } from '@ant-design/icons'
import { Badge, Button, Empty, Flex, List, Popover, Typography } from 'antd'
import dayjs from 'dayjs'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router'
import { useAlerts, useReadAlert } from '../api/alerts'

/** Дзвіночок у шапці: мої алерти (критична несправність з чек-листа…). */
export function AlertsBell() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const alerts = useAlerts()
  const read = useReadAlert()
  const [open, setOpen] = useState(false)
  const data = alerts.data

  const content = (
    <Flex vertical style={{ width: 380, maxHeight: 420, overflow: 'auto' }}>
      {!data || data.items.length === 0 ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('alerts.empty')} />
      ) : (
        <List
          size="small"
          dataSource={data.items}
          renderItem={(a) => (
            <List.Item
              style={{ cursor: 'pointer', opacity: a.read ? 0.6 : 1 }}
              onClick={() => {
                if (!a.read) read.mutate(a.id)
                if (a.projectId) {
                  setOpen(false)
                  navigate(`/projects/${a.projectId}`)
                }
              }}
            >
              <Flex vertical>
                <Typography.Text strong={!a.read}>
                  {t(`alerts.kinds.${a.kind}`, { defaultValue: a.kind })}
                  {a.taskNumber != null && ` · #${a.taskNumber}`}
                </Typography.Text>
                <Typography.Text>{a.title}</Typography.Text>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {dayjs(a.createdAt).format('DD.MM HH:mm')}
                </Typography.Text>
              </Flex>
            </List.Item>
          )}
        />
      )}
    </Flex>
  )

  return (
    <Popover
      open={open}
      onOpenChange={setOpen}
      trigger="click"
      placement="bottomRight"
      title={
        <Flex justify="space-between" align="center">
          {t('alerts.title')}
          {!!data?.unread && (
            <Button size="small" type="link" onClick={() => read.mutate(undefined)}>
              {t('alerts.readAll')}
            </Button>
          )}
        </Flex>
      }
      content={content}
    >
      <Badge count={data?.unread ?? 0} size="small">
        <Button icon={<BellOutlined />} aria-label={t('alerts.title')} />
      </Badge>
    </Popover>
  )
}
