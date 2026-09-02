import { Alert, Descriptions } from 'antd'
import { Clock3, Video } from 'lucide-react'

import type { AnnotationTask } from '@/api/annotation'
import type { QualityReview } from '@/api/quality'
import { MediaReportCard, PlaybackCard } from '@/components/AssetDetailSections'
import { StatusIndicator } from '@/components/status/StatusIndicator'
import { Section } from '@/components/workspace/Section'
import type { GroupViewRow } from '@/features/capture-group/capture-group-detail-model'
import type { CaptureGroupViewState } from '@/features/capture-group/use-capture-group-view'
import { mediaStatusLabels, viewQualityLabels } from '@/features/capture-group-presentation'

interface CaptureGroupFocusedViewProps {
  viewRows: GroupViewRow[]
  task: AnnotationTask | null
  viewReviews: Record<string, QualityReview>
  viewState: CaptureGroupViewState
  onSelectView: (clipAssetId: string) => void
  isCompleted?: boolean
}

export function CaptureGroupFocusedView({
  viewRows,
  task,
  viewReviews,
  viewState,
  onSelectView,
  isCompleted = false,
}: CaptureGroupFocusedViewProps) {
  const {
    selectedStream,
    selectedDetail,
    selectedMediaReport,
    selectedMediaReportState,
    selectedMediaReportError,
    candidateResponse,
    candidateState,
    candidateDescription: currentCandidateDescription,
    candidateTone: currentCandidateTone,
    playbackGrant,
    playbackState,
    playbackMessage,
    playbackAvailability,
    isRequestingPlayback,
    canPlay,
    selectedSync,
    resetPlayback,
    loadSelectedView,
    startPlayback,
    onLoadedMetadata,
    onPlaybackError,
  } = viewState
  const isPrimary = Boolean(
    selectedStream && task?.primary_clip_asset_id === selectedStream.clip_asset_id,
  )
  const isSupporting = Boolean(selectedStream && task?.primary_clip_asset_id && !isPrimary)

  const focusedView = (
    <section
      className="capture-group-focused-workspace"
      aria-labelledby="capture-group-focused-title"
    >
      <header className="capture-group-focused-heading">
        <div>
          <p className="app-eyebrow">当前视图</p>
          <h2 id="capture-group-focused-title">
            {selectedStream ? `机位 ${selectedStream.camera_number}` : '等待选择机位'}
          </h2>
          {selectedStream ? (
            <span className="capture-group-focused-role">{selectedStream.camera_role}</span>
          ) : null}
        </div>
        {isPrimary || isSupporting ? (
          <StatusIndicator tone="information">
            {isPrimary ? '主视图 · 正式时间基准' : '参考视图 · 事件级证据'}
          </StatusIndicator>
        ) : null}
      </header>
      {isSupporting ? (
        <Alert
          className="capture-group-supporting-warning"
          type="info"
          showIcon
          title="当前为参考视图"
          description="仅供人工视觉对照；正式标注时间基准仍以主视图为准。"
        />
      ) : null}
      {viewRows.length > 1 ? (
        <div className="capture-group-compare-strip" role="group" aria-label="配药事件机位选择">
          <div className="capture-group-compare-heading">
            <span className="app-eyebrow">机位切换</span>
            <span>选择一路机位查看当前视频；缺失机位保留在原编号位置。</span>
          </div>
          <div className="capture-group-compare-list">
            {viewRows.map((view) => {
              const stream = view.stream
              const isCurrent = Boolean(
                stream && stream.clip_asset_id === selectedStream?.clip_asset_id,
              )
              const decision = viewReviews[stream?.clip_asset_id ?? ''] ?? null
              const qualityLabel = stream
                ? `机位质量 · ${viewQualityLabels[decision?.decision ?? 'UNREVIEWED']}`
                : null
              const mediaLabel = stream
                ? (mediaStatusLabels[stream.stream_status] ?? '媒体状态暂不可用')
                : '缺失'
              return (
                <button
                  className={`capture-group-compare-item${isCurrent ? ' is-current' : ''}`}
                  type="button"
                  key={view.cameraNumber}
                  disabled={!stream}
                  aria-pressed={isCurrent}
                  aria-current={isCurrent ? 'true' : undefined}
                  aria-label={[`机位 ${view.cameraNumber}`, mediaLabel, qualityLabel]
                    .filter(Boolean)
                    .join(' · ')}
                  onClick={() => stream && onSelectView(stream.clip_asset_id)}
                >
                  <span className="capture-group-compare-thumbnail" aria-hidden="true">
                    <Video size={16} strokeWidth={1.7} />
                  </span>
                  <span>
                    <strong>机位 {view.cameraNumber}</strong>
                    <small>{mediaLabel}</small>
                    {qualityLabel ? <small>{qualityLabel}</small> : null}
                  </span>
                </button>
              )
            })}
          </div>
        </div>
      ) : null}
      <PlaybackCard
        availability={playbackAvailability}
        candidateOverlay={candidateResponse?.candidates ?? []}
        canPlay={Boolean(canPlay)}
        canRecoverMissingMediaProbe={false}
        grant={playbackGrant}
        isRecoveringMediaProbe={false}
        isRequestingPlayback={isRequestingPlayback}
        mediaProbeMessage={
          selectedDetail?.status === 'INGEST_FAILED'
            ? '媒体检测失败是媒体事实；不改变接收状态，也不自动判定机位质量。'
            : ''
        }
        playbackMessage={playbackMessage}
        playbackState={playbackState}
        onClose={resetPlayback}
        onLoadedMetadata={onLoadedMetadata}
        onPlaybackError={onPlaybackError}
        onRecoverMissingMediaProbe={() => undefined}
        onStartPlayback={() => void startPlayback()}
      />
      {selectedDetail ? (
        <details className="capture-group-focused-details">
          <summary>查看机位技术与辅助证据</summary>
          <div className="capture-group-focused-details-content">
            {selectedSync ? (
              <div className="capture-group-sync-note capture-group-stack" role="status">
                <Clock3 aria-hidden="true" size={16} strokeWidth={1.8} />
                <div>
                  <StatusIndicator tone={selectedSync.tone}>{selectedSync.label}</StatusIndicator>
                  <p>{selectedSync.detail}</p>
                </div>
              </div>
            ) : null}
            <div className="capture-group-focused-facts">
              <Section title="手部检测" titleId="capture-group-candidate-title">
                <div className="capture-group-candidate-facts capture-group-stack">
                  <StatusIndicator tone={currentCandidateTone}>
                    {candidateResponse?.job.hand_result === 'NO_HAND_DETECTED'
                      ? '未检测到手部'
                      : candidateResponse?.job.status === 'FAILED' || candidateState === 'error'
                        ? '检测失败'
                        : candidateResponse?.job.status === 'SUCCEEDED' &&
                            candidateResponse.job.hand_result === 'HAND_DETECTED'
                          ? '检测到手部'
                          : candidateResponse?.job.status === 'SUCCEEDED' &&
                              candidateResponse.job.hand_result === 'UNKNOWN'
                            ? '检测结果未知'
                            : candidateResponse?.job.status === 'SUCCEEDED'
                              ? '检测完成'
                              : candidateState === 'unavailable'
                                ? '尚无检测结果'
                                : '读取中'}
                  </StatusIndicator>
                  <p>AI 证据仅供人工参考，不自动决定机位质量或事件准入。</p>
                  <p>{currentCandidateDescription}</p>
                  {candidateResponse?.job.run_summary ? (
                    <Descriptions
                      column={{ xs: 1, sm: 2 }}
                      items={[
                        {
                          key: 'frames',
                          label: '分析帧数',
                          children: candidateResponse.job.run_summary.inference_frame_count,
                        },
                        {
                          key: 'hands',
                          label: '发现手部帧',
                          children: candidateResponse.job.run_summary.frames_with_hand,
                        },
                        {
                          key: 'left',
                          label: '左手检测次数',
                          children: candidateResponse.job.run_summary.left_hand_detection_count,
                        },
                        {
                          key: 'right',
                          label: '右手检测次数',
                          children: candidateResponse.job.run_summary.right_hand_detection_count,
                        },
                        ...(candidateResponse.job.run_summary.failure_reason
                          ? [
                              {
                                key: 'failure',
                                label: '失败原因',
                                children: candidateResponse.job.run_summary.failure_reason,
                              },
                            ]
                          : []),
                      ]}
                    />
                  ) : null}
                </div>
              </Section>
              <MediaReportCard
                detail={selectedDetail}
                errorCode={selectedMediaReportState === 'error' ? 'MEDIA_REPORT_UNAVAILABLE' : ''}
                errorMessage={selectedMediaReportError}
                loadState={selectedMediaReportState}
                onRetry={() => void loadSelectedView()}
                report={selectedMediaReport}
              />
            </div>
          </div>
        </details>
      ) : null}
    </section>
  )

  return isCompleted ? (
    <details className="detail-disclosure capture-group-completed-video-details">
      <summary>查看视频与辅助证据</summary>
      <div className="detail-disclosure-content capture-group-completed-video-content">
        {focusedView}
      </div>
    </details>
  ) : (
    focusedView
  )
}
