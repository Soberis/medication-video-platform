import { Alert, Descriptions } from 'antd'
import { Clock3, Video } from 'lucide-react'

import type { AnnotationTask } from '@/api/annotation'
import type { QualityReview } from '@/api/quality'
import { MediaReportCard, PlaybackCard } from '@/components/AssetDetailSections'
import { StatusIndicator } from '@/components/status/StatusIndicator'
import { Section } from '@/components/workspace/Section'
import type { GroupViewRow } from '@/features/capture-group/capture-group-detail-model'
import type { CaptureGroupViewState } from '@/features/capture-group/use-capture-group-view'
import { viewQualityLabels } from '@/features/capture-group-presentation'

interface CaptureGroupFocusedViewProps {
  viewRows: GroupViewRow[]
  task: AnnotationTask | null
  viewReviews: Record<string, QualityReview>
  viewState: CaptureGroupViewState
  onSelectView: (clipAssetId: string) => void
}

export function CaptureGroupFocusedView({
  viewRows,
  task,
  viewReviews,
  viewState,
  onSelectView,
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

  return (
    <section
      className="capture-group-focused-workspace"
      aria-labelledby="capture-group-focused-title"
    >
      <header className="capture-group-focused-heading">
        <div>
          <p className="app-eyebrow">Focused workspace</p>
          <h2 id="capture-group-focused-title">
            {selectedStream
              ? `Cam ${selectedStream.camera_number} · ${selectedStream.camera_role}`
              : '等待选择视图'}
          </h2>
        </div>
        {selectedStream ? (
          <StatusIndicator tone="information">
            {task?.primary_clip_asset_id === selectedStream.clip_asset_id
              ? 'Primary View'
              : 'Supporting View'}
          </StatusIndicator>
        ) : null}
      </header>
      {selectedStream &&
      task?.primary_clip_asset_id &&
      task.primary_clip_asset_id !== selectedStream.clip_asset_id ? (
        <Alert
          className="capture-group-supporting-warning"
          type="info"
          showIcon
          title="当前为 Supporting View"
          description="可用于人工视觉对照；正式 Annotation timeline 仍以 Primary View 为时间基准。"
        />
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
            ? 'Media Probe failure 是媒体事实；不改变 Receipt，也不自动判定 View Quality。'
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
      {selectedStream ? (
        <div className="capture-group-compare-strip" aria-label="Supporting View 相机切换">
          <div className="capture-group-compare-heading">
            <span className="app-eyebrow">Camera switcher</span>
            <span>只切换当前主播放器，不预加载其他完整视频。</span>
          </div>
          <div className="capture-group-compare-list">
            {viewRows.map((view) => {
              const stream = view.stream
              const isCurrent = stream?.clip_asset_id === selectedStream.clip_asset_id
              const decision = viewReviews[stream?.clip_asset_id ?? ''] ?? null
              return (
                <button
                  className={`capture-group-compare-item${isCurrent ? ' is-current' : ''}`}
                  type="button"
                  key={view.cameraNumber}
                  disabled={!stream}
                  aria-pressed={isCurrent}
                  onClick={() => stream && onSelectView(stream.clip_asset_id)}
                >
                  <span className="capture-group-compare-thumbnail" aria-hidden="true">
                    <Video size={16} strokeWidth={1.7} />
                  </span>
                  <span>
                    <strong>Cam {view.cameraNumber}</strong>
                    <small>
                      {stream ? viewQualityLabels[decision?.decision ?? 'UNREVIEWED'] : '缺失'}
                      {stream && stream.stream_status !== 'INGESTED' ? ' · 媒体不可用' : ''}
                    </small>
                  </span>
                </button>
              )
            })}
          </div>
        </div>
      ) : null}
      {selectedSync ? (
        <div className="capture-group-sync-note capture-group-stack" role="status">
          <Clock3 aria-hidden="true" size={16} strokeWidth={1.8} />
          <div>
            <StatusIndicator tone={selectedSync.tone}>{selectedSync.label}</StatusIndicator>
            <p>{selectedSync.detail}</p>
          </div>
        </div>
      ) : null}
      {selectedDetail ? (
        <div className="capture-group-focused-facts">
          <Section title="AI Candidate facts" titleId="capture-group-candidate-title">
            <div className="capture-group-candidate-facts capture-group-stack">
              <StatusIndicator tone={currentCandidateTone}>
                {candidateResponse?.job.hand_result === 'NO_HAND_DETECTED'
                  ? 'NO_HAND_DETECTED'
                  : candidateResponse?.job.status === 'FAILED' || candidateState === 'error'
                    ? 'AI FAILED'
                    : candidateResponse?.job.status === 'SUCCEEDED'
                      ? 'Candidate ready'
                      : candidateState === 'unavailable'
                        ? '尚无 Candidate'
                        : '读取中'}
              </StatusIndicator>
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
                      label: '左手候选',
                      children: candidateResponse.job.run_summary.left_hand_detection_count,
                    },
                    {
                      key: 'right',
                      label: '右手候选',
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
      ) : null}
    </section>
  )
}
