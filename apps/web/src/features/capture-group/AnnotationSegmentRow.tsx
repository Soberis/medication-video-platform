import { Button, Select } from 'antd'
import { Clock, Play, Trash2 } from 'lucide-react'
import type { AnnotationLabelSchema } from '@/api/annotation'
import {
  formatAnnotationTime,
  formatHumanFriendlyTime,
  formatSegmentDurationLabel,
  getActionDisplayName,
  getActionStyle,
  type DraftAnnotationSegment,
  type SegmentValidationErrors,
} from '@/features/capture-group/capture-group-detail-model'

export interface AnnotationSegmentRowProps {
  segment: DraftAnnotationSegment
  index: number
  totalCount: number
  schema: AnnotationLabelSchema | null
  sortedLabels: Array<{ label_id: string; display_name: string }>
  errors: SegmentValidationErrors
  collapsed: boolean
  canEdit: boolean
  isPlayingActive: boolean
  canUseCurrentPlaybackTime: boolean
  onToggleCollapse: (draftId: number) => void
  onPlaySegment?: (startMs: number, endMs: number) => void
  onDeleteSegment: (draftId: number) => void
  onUpdateField: (
    draftId: number,
    field: 'start_ms' | 'end_ms' | 'label',
    value: string | number,
  ) => void
  onNudgeTime: (draftId: number, field: 'start_ms' | 'end_ms', delta: number) => void
  onSetCurrentTime: (draftId: number, field: 'start_ms' | 'end_ms') => void
}

export function AnnotationSegmentRow({
  segment,
  index,
  totalCount,
  schema,
  sortedLabels,
  errors,
  collapsed,
  canEdit,
  isPlayingActive,
  canUseCurrentPlaybackTime,
  onToggleCollapse,
  onPlaySegment,
  onDeleteSegment,
  onUpdateField,
  onNudgeTime,
  onSetCurrentTime,
}: AnnotationSegmentRowProps) {
  const formattedStart = formatAnnotationTime(segment.start_ms)
  const formattedEnd = formatAnnotationTime(segment.end_ms)
  const startReadableId = `segment-${segment.draftId}-start-readable`
  const endReadableId = `segment-${segment.draftId}-end-readable`
  const startErrorId = `segment-${segment.draftId}-start-error`
  const endErrorId = `segment-${segment.draftId}-end-error`
  const startDescribedBy =
    [formattedStart ? startReadableId : '', errors.start ? startErrorId : '']
      .filter(Boolean)
      .join(' ') || undefined
  const endDescribedBy =
    [formattedEnd ? endReadableId : '', errors.end ? endErrorId : ''].filter(Boolean).join(' ') ||
    undefined
  const segmentStyle = getActionStyle(segment.label)

  return (
    <div
      id={`segment-row-${segment.draftId}`}
      className={`capture-group-segment-row ${isPlayingActive ? 'is-playing-active' : ''} ${collapsed ? 'is-collapsed' : 'is-expanded'}`}
    >
      <div className="capture-group-segment-fields">
        <div className="capture-group-segment-card-header">
          <div className="capture-group-segment-card-main">
            <button
              type="button"
              className="capture-group-segment-badge-group"
              aria-expanded={!collapsed}
              onClick={() => onToggleCollapse(segment.draftId)}
              title={collapsed ? '点击展开微调' : '点击折叠'}
            >
              <span
                className="capture-group-segment-dot"
                style={{
                  display: 'inline-block',
                  width: 7,
                  height: 7,
                  borderRadius: '50%',
                  backgroundColor: segmentStyle.dot,
                  flexShrink: 0,
                }}
                aria-hidden="true"
              />
              <strong className="capture-group-segment-index-title">
                #{String(index + 1).padStart(2, '0')}
              </strong>
              <span
                className="capture-group-segment-action-name"
                title={segment.label ? getActionDisplayName(segment.label, schema) : '未选择动作'}
              >
                {segment.label ? getActionDisplayName(segment.label, schema) : '未选择动作'}
              </span>
              <span
                className="capture-group-segment-human-time"
                title={formatSegmentDurationLabel(segment.start_ms, segment.end_ms)}
              >
                {collapsed
                  ? `${formatHumanFriendlyTime(segment.start_ms)} ~ ${formatHumanFriendlyTime(segment.end_ms)}`
                  : formatSegmentDurationLabel(segment.start_ms, segment.end_ms)}
              </span>
            </button>
            <div className="capture-group-segment-card-actions">
              {onPlaySegment &&
              Number.isFinite(segment.start_ms) &&
              Number.isFinite(segment.end_ms) &&
              segment.end_ms > segment.start_ms ? (
                <Button
                  type="text"
                  size="small"
                  htmlType="button"
                  className="capture-group-segment-play-btn"
                  icon={<Play size={12} fill="currentColor" />}
                  aria-label={`播放第 ${index + 1} 个时间段`}
                  onClick={(e) => {
                    e.stopPropagation()
                    onPlaySegment(segment.start_ms, segment.end_ms)
                  }}
                  title={`播放时间段 (${formatHumanFriendlyTime(segment.start_ms)} ~ ${formatHumanFriendlyTime(segment.end_ms)})`}
                />
              ) : null}
              {totalCount > 1 ? (
                <Button
                  type="text"
                  size="small"
                  htmlType="button"
                  className="capture-group-segment-delete-btn"
                  icon={<Trash2 size={12} />}
                  disabled={!canEdit}
                  aria-label={`删除第 ${index + 1} 个时间段`}
                  onClick={(e) => {
                    e.stopPropagation()
                    onDeleteSegment(segment.draftId)
                  }}
                  title={`删除第 ${index + 1} 个时间段`}
                />
              ) : null}
            </div>
          </div>
          {!collapsed ? (
            <label
              className="capture-group-segment-inline-label"
              htmlFor={`segment-${segment.draftId}-label`}
            >
              <span>动作标签</span>
              <Select
                id={`segment-${segment.draftId}-label`}
                size="small"
                aria-label={`第 ${index + 1} 个时间段动作标签`}
                aria-invalid={errors.label ? true : undefined}
                aria-describedby={
                  errors.label ? `segment-${segment.draftId}-label-error` : undefined
                }
                value={segment.label || undefined}
                placeholder="选择标签"
                disabled={!canEdit || !sortedLabels.length}
                options={sortedLabels.map((label) => ({
                  value: label.label_id,
                  label: label.display_name,
                }))}
                onChange={(value) => onUpdateField(segment.draftId, 'label', value)}
              />
              {errors.label ? (
                <small id={`segment-${segment.draftId}-label-error`} role="alert">
                  {errors.label}
                </small>
              ) : null}
            </label>
          ) : null}
        </div>
        {!collapsed ? (
          <div className="capture-group-segment-edit-body">
            <fieldset className="capture-group-segment-time-range">
              <legend className="capture-group-segment-legend">时间范围</legend>
              <div className="capture-group-segment-time-controls">
                <div className="capture-group-segment-time-field">
                  <div className="capture-group-segment-time-field-header">
                    <label htmlFor={`segment-${segment.draftId}-start`}>开始时间（毫秒）</label>
                    {formattedStart ? (
                      <span id={startReadableId} className="capture-group-segment-time-readable">
                        {formattedStart}
                      </span>
                    ) : null}
                  </div>
                  <div className="capture-group-segment-time-input-wrap">
                    <input
                      id={`segment-${segment.draftId}-start`}
                      name={`segment-${segment.draftId}-start-ms`}
                      type="number"
                      min={0}
                      step={1}
                      inputMode="numeric"
                      autoComplete="off"
                      value={Number.isFinite(segment.start_ms) ? segment.start_ms : ''}
                      aria-invalid={errors.start ? true : undefined}
                      aria-describedby={startDescribedBy}
                      disabled={!canEdit}
                      onChange={(event) =>
                        onUpdateField(
                          segment.draftId,
                          'start_ms',
                          event.target.value === '' ? Number.NaN : Number(event.target.value),
                        )
                      }
                      onKeyDown={(event) => {
                        if (event.key === 'ArrowUp' || event.key === 'ArrowDown') {
                          event.preventDefault()
                          const multiplier = event.key === 'ArrowUp' ? 1 : -1
                          const delta = (event.shiftKey ? 500 : 100) * multiplier
                          onNudgeTime(segment.draftId, 'start_ms', delta)
                        }
                      }}
                    />
                    <button
                      type="button"
                      className="capture-group-segment-nudge-btn"
                      disabled={!canEdit}
                      aria-label={`第 ${index + 1} 个时间段：开始时间减少 100 毫秒`}
                      title="微调 -100ms（按住 Shift 微调 -500ms）"
                      onClick={(e) => {
                        const delta = e.shiftKey ? -500 : -100
                        onNudgeTime(segment.draftId, 'start_ms', delta)
                      }}
                    >
                      -100
                    </button>
                    <button
                      type="button"
                      className="capture-group-segment-nudge-btn"
                      disabled={!canEdit}
                      aria-label={`第 ${index + 1} 个时间段：开始时间增加 100 毫秒`}
                      title="微调 +100ms（按住 Shift 微调 +500ms）"
                      onClick={(e) => {
                        const delta = e.shiftKey ? 500 : 100
                        onNudgeTime(segment.draftId, 'start_ms', delta)
                      }}
                    >
                      +100
                    </button>
                    <Button
                      type="text"
                      size="small"
                      htmlType="button"
                      className="capture-group-segment-set-current-btn"
                      icon={<Clock size={11} strokeWidth={2} />}
                      disabled={!canUseCurrentPlaybackTime}
                      aria-label={`第 ${index + 1} 个时间段：使用当前时间作为开始`}
                      title="使用当前播放时间作为开始时间"
                      onClick={() => onSetCurrentTime(segment.draftId, 'start_ms')}
                    >
                      当前
                    </Button>
                  </div>
                  {errors.start ? (
                    <small id={startErrorId} role="alert">
                      {errors.start}
                    </small>
                  ) : null}
                </div>
                <span className="capture-group-segment-time-arrow" aria-hidden="true">
                  →
                </span>
                <div className="capture-group-segment-time-field">
                  <div className="capture-group-segment-time-field-header">
                    <label htmlFor={`segment-${segment.draftId}-end`}>结束时间（毫秒）</label>
                    {formattedEnd ? (
                      <span id={endReadableId} className="capture-group-segment-time-readable">
                        {formattedEnd}
                      </span>
                    ) : null}
                  </div>
                  <div className="capture-group-segment-time-input-wrap">
                    <input
                      id={`segment-${segment.draftId}-end`}
                      name={`segment-${segment.draftId}-end-ms`}
                      type="number"
                      min={1}
                      step={1}
                      inputMode="numeric"
                      autoComplete="off"
                      value={Number.isFinite(segment.end_ms) ? segment.end_ms : ''}
                      aria-invalid={errors.end ? true : undefined}
                      aria-describedby={endDescribedBy}
                      disabled={!canEdit}
                      onChange={(event) =>
                        onUpdateField(
                          segment.draftId,
                          'end_ms',
                          event.target.value === '' ? Number.NaN : Number(event.target.value),
                        )
                      }
                      onKeyDown={(event) => {
                        if (event.key === 'ArrowUp' || event.key === 'ArrowDown') {
                          event.preventDefault()
                          const multiplier = event.key === 'ArrowUp' ? 1 : -1
                          const delta = (event.shiftKey ? 500 : 100) * multiplier
                          onNudgeTime(segment.draftId, 'end_ms', delta)
                        }
                      }}
                    />
                    <button
                      type="button"
                      className="capture-group-segment-nudge-btn"
                      disabled={!canEdit}
                      aria-label={`第 ${index + 1} 个时间段：结束时间减少 100 毫秒`}
                      title="微调 -100ms（按住 Shift 微调 -500ms）"
                      onClick={(e) => {
                        const delta = e.shiftKey ? -500 : -100
                        onNudgeTime(segment.draftId, 'end_ms', delta)
                      }}
                    >
                      -100
                    </button>
                    <button
                      type="button"
                      className="capture-group-segment-nudge-btn"
                      disabled={!canEdit}
                      aria-label={`第 ${index + 1} 个时间段：结束时间增加 100 毫秒`}
                      title="微调 +100ms（按住 Shift 微调 +500ms）"
                      onClick={(e) => {
                        const delta = e.shiftKey ? 500 : 100
                        onNudgeTime(segment.draftId, 'end_ms', delta)
                      }}
                    >
                      +100
                    </button>
                    <Button
                      type="text"
                      size="small"
                      htmlType="button"
                      className="capture-group-segment-set-current-btn"
                      icon={<Clock size={11} strokeWidth={2} />}
                      disabled={!canUseCurrentPlaybackTime}
                      aria-label={`第 ${index + 1} 个时间段：使用当前时间作为结束`}
                      title="使用当前播放时间作为结束时间"
                      onClick={() => onSetCurrentTime(segment.draftId, 'end_ms')}
                    >
                      当前
                    </Button>
                  </div>
                  {errors.end ? (
                    <small id={endErrorId} role="alert">
                      {errors.end}
                    </small>
                  ) : null}
                </div>
              </div>
            </fieldset>
          </div>
        ) : null}
      </div>
    </div>
  )
}
