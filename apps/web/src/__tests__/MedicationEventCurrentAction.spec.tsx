import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import { MedicationEventCurrentAction } from '@/features/capture-group/MedicationEventCurrentAction'
import type {
  MedicationEventWorkflowModel,
  MedicationEventWorkflowPrimaryAction,
} from '@/features/capture-group/medication-event-workflow'

const viewQualityAction: MedicationEventWorkflowPrimaryAction = {
  key: 'decide-view-quality',
  label: '判断当前机位质量',
  target: 'view-quality',
}

function workflow(
  overrides: Partial<MedicationEventWorkflowModel> = {},
): MedicationEventWorkflowModel {
  return {
    stage: 'VIEW_QUALITY',
    steps: [
      {
        stage: 'VIEW_QUALITY',
        label: '机位质量',
        state: 'current',
      },
    ],
    primaryAction: viewQualityAction,
    blocker: null,
    waitingReason: null,
    focusedViewId: 'clip-1',
    ...overrides,
  }
}

describe('MedicationEventCurrentAction', () => {
  it('shows the colocated action as text without a redundant navigation button', () => {
    render(
      <MedicationEventCurrentAction
        workflow={workflow()}
        primaryActionHasForm
        onPrimaryAction={vi.fn()}
      />,
    )

    expect(screen.getByText('判断当前机位质量')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '判断当前机位质量' })).not.toBeInTheDocument()
  })

  it('keeps a CTA when the action is a real navigation', () => {
    const onPrimaryAction = vi.fn()
    render(<MedicationEventCurrentAction workflow={workflow()} onPrimaryAction={onPrimaryAction} />)

    const button = screen.getByRole('button', { name: '判断当前机位质量' })
    expect(button).toHaveClass('ant-btn-primary')
  })

  it('uses information for waiting and warning for a temporary blocker', () => {
    const { unmount } = render(
      <MedicationEventCurrentAction
        workflow={workflow({ primaryAction: null, waitingReason: '正在读取事实。' })}
      />,
    )
    const waitingAction = screen
      .getByText('正在读取事实。')
      .closest('.capture-group-current-action')
    expect(waitingAction?.querySelector('.status-indicator')).toHaveAttribute(
      'data-tone',
      'information',
    )
    unmount()

    render(
      <MedicationEventCurrentAction
        workflow={workflow({ primaryAction: null, blocker: '事实暂不可用，请刷新后重试。' })}
      />,
    )
    const action = screen
      .getByText('事实暂不可用，请刷新后重试。')
      .closest('.capture-group-current-action')
    expect(action?.querySelector('.status-indicator')).toHaveAttribute('data-tone', 'warning')
    expect(action?.querySelector('.status-indicator')).not.toHaveAttribute(
      'data-tone',
      'destructive',
    )
  })

  it('shows a completed result summary without a primary action', () => {
    render(
      <MedicationEventCurrentAction
        workflow={workflow({
          stage: 'TRAINING_ELIGIBILITY',
          steps: [
            {
              stage: 'TRAINING_ELIGIBILITY',
              label: '训练资格检查',
              state: 'completed',
            },
          ],
          primaryAction: null,
        })}
        onPrimaryAction={vi.fn()}
      />,
    )

    expect(screen.getByRole('heading', { level: 2, name: '当前结果' })).toBeInTheDocument()
    expect(screen.getByText('流程已完成')).toBeInTheDocument()
    expect(screen.getByText('人工复核：已通过')).toBeInTheDocument()
    expect(screen.getByText('训练资格检查：已通过')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '查看复核与训练资格详情 →' })).toHaveAttribute(
      'href',
      '#capture-group-review-cleaning',
    )
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })
})
