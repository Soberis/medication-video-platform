import { App as AntdApp } from 'antd'
import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import type { ClipAssetRead, MediaReport } from '@/api/catalog'
import { MediaReportCard } from '@/components/AssetDetailSections'

const detail: ClipAssetRead = {
  clip_asset_id: 'clip-asset-1',
  batch_id: 'batch-1',
  source_upload_file_id: 'upload-file-1',
  client_file_id: 'client-file-1',
  content_type: 'video/mp4',
  size_bytes: 1024,
  sha256: 'a'.repeat(64),
  status: 'INGESTED',
  session_binding: null,
  created_at: '2026-09-16T00:00:00Z',
  updated_at: '2026-09-16T00:00:00Z',
}

const report: MediaReport = {
  media_report_id: 'media-report-1',
  media_job_id: 'media-job-1',
  project_id: 'project-1',
  clip_asset_id: detail.clip_asset_id,
  container_format: 'mp4',
  duration_microseconds: 1_000_000,
  video_codec: 'h264',
  video_profile: 'High',
  width: 1280,
  height: 720,
  pixel_format: 'yuv420p',
  primary_video_bitrate_bps: 2_000_000,
  average_frame_rate_numerator: 30,
  average_frame_rate_denominator: 1,
  frame_count: 30,
  variable_frame_rate: true,
  audio_stream_count: 1,
  decode_verified: true,
  input_digest_verified: true,
  warning_codes: ['VFR_DETECTED', 'AUDIO_PRESENT'],
  created_at: '2026-09-16T00:00:00Z',
}

describe('media report presentation', () => {
  it('renders non-blocking media notes as compact neutral tags', () => {
    render(
      <AntdApp>
        <MediaReportCard
          detail={detail}
          errorCode=""
          errorMessage=""
          loadState="success"
          onRetry={vi.fn()}
          report={report}
        />
      </AntdApp>,
    )

    expect(screen.getByLabelText('媒体附加信息')).toBeInTheDocument()
    expect(screen.getByText('可变帧率')).toBeInTheDocument()
    expect(screen.getByText('包含音轨')).toBeInTheDocument()
    expect(screen.queryByText('检测发现需关注项')).not.toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})
