import { Button } from 'antd'
import { CircleAlert, CircleCheck, Clock3 } from 'lucide-react'
import type { Ref } from 'react'

import { Section } from '@/components/workspace/Section'
import { StatusIndicator } from '@/components/status/StatusIndicator'
import {
  medicationEventWorkflowStageLabels,
  type MedicationEventWorkflowModel,
  type MedicationEventWorkflowPrimaryAction,
  type MedicationEventWorkflowStepState,
} from '@/features/capture-group/medication-event-workflow'

interface MedicationEventCurrentActionProps {
  workflow: MedicationEventWorkflowModel
  onPrimaryAction?: (action: MedicationEventWorkflowPrimaryAction) => void
  primaryActionHasForm?: boolean
  primaryActionDisabled?: boolean
  primaryActionLoading?: boolean
  primaryActionRef?: Ref<HTMLButtonElement>
}

const stepStateLabels: Record<MedicationEventWorkflowStepState, string> = {
  completed: '已完成',
  current: '当前',
  waiting: '等待',
  blocked: '暂不可确认',
  upcoming: '未开始',
}

function stepStateIcon(state: MedicationEventWorkflowStepState) {
  if (state === 'completed') return <CircleCheck aria-hidden="true" size={14} strokeWidth={1.8} />
  if (state === 'waiting') return <Clock3 aria-hidden="true" size={14} strokeWidth={1.8} />
  if (state === 'blocked') return <CircleAlert aria-hidden="true" size={14} strokeWidth={1.8} />
  return null
}

/**
 * The workflow is an informational progress rail, not a second navigation
 * surface. Stage changes still come from the server-backed workflow model.
 */
export function MedicationEventWorkflowRail({
  workflow,
}: {
  workflow: MedicationEventWorkflowModel
}) {
  return (
    <details className="capture-group-workflow-rail capture-group-workflow-disclosure">
      <summary>查看完整流程</summary>
      <div className="capture-group-workflow-rail-content">
        <WorkflowRailContent workflow={workflow} />
      </div>
    </details>
  )
}

function WorkflowRailContent({ workflow }: { workflow: MedicationEventWorkflowModel }) {
  return (
    <>
      <div className="capture-group-workflow-rail-heading">
        <span className="app-eyebrow">处理进度</span>
        <span>当前阶段：{medicationEventWorkflowStageLabels[workflow.stage]}</span>
      </div>
      <ol className="capture-group-workflow-rail-list" aria-label="配药事件处理流程">
        {workflow.steps.map((step) => (
          <li
            className={`capture-group-workflow-rail-step is-${step.state}`}
            data-state={step.state}
            key={step.stage}
            aria-current={step.stage === workflow.stage ? 'step' : undefined}
          >
            <span
              className="capture-group-workflow-rail-marker"
              data-state={step.state}
              aria-hidden="true"
            >
              {stepStateIcon(step.state)}
            </span>
            <span className="capture-group-workflow-rail-copy">
              <strong>{step.label}</strong>
              <small>{stepStateLabels[step.state]}</small>
            </span>
          </li>
        ))}
      </ol>
    </>
  )
}

function actionMessage(workflow: MedicationEventWorkflowModel, primaryActionHasForm: boolean) {
  if (workflow.blocker) return workflow.blocker
  if (workflow.waitingReason) return workflow.waitingReason
  if (primaryActionHasForm && workflow.primaryAction) return '在下方直接完成当前动作。'
  if (workflow.primaryAction) return '根据当前服务端事实完成这一项处理。'
  return workflow.stage === 'CLEANING' ? '当前配药事件工作流已完成。' : '当前没有可执行的处理动作。'
}

export function MedicationEventCurrentAction({
  workflow,
  onPrimaryAction,
  primaryActionHasForm = false,
  primaryActionDisabled = false,
  primaryActionLoading = false,
  primaryActionRef,
}: MedicationEventCurrentActionProps) {
  const primaryAction = workflow.primaryAction
  const message = actionMessage(workflow, primaryActionHasForm)
  const isCompleted = workflow.steps.every((step) => step.state === 'completed')

  return (
    <Section
      className={`capture-group-current-action${isCompleted ? ' is-completed' : ''}`}
      title={isCompleted ? '当前结果' : '当前动作'}
      titleId="capture-group-current-action-title"
    >
      {isCompleted ? (
        <CompletedResultSummary />
      ) : (
        <div className="capture-group-current-action-summary">
          <div className="capture-group-current-action-copy">
            <span className="app-eyebrow">当前阶段</span>
            <StatusIndicator tone={workflow.blocker ? 'warning' : 'information'}>
              {medicationEventWorkflowStageLabels[workflow.stage]}
            </StatusIndicator>
            {primaryActionHasForm && primaryAction ? (
              <strong className="capture-group-current-action-form-label">
                {primaryAction.label}
              </strong>
            ) : null}
            <p
              className={`capture-group-current-action-message${workflow.blocker ? ' is-blocked' : ''}`}
              role="status"
              aria-live="polite"
            >
              {message}
            </p>
          </div>
          {primaryAction && onPrimaryAction && !primaryActionHasForm ? (
            <Button
              ref={primaryActionRef}
              type="primary"
              htmlType="button"
              loading={primaryActionLoading}
              disabled={primaryActionDisabled}
              onClick={() => onPrimaryAction(primaryAction)}
            >
              {primaryAction.label}
            </Button>
          ) : null}
        </div>
      )}
    </Section>
  )
}

function CompletedResultSummary() {
  return (
    <div className="capture-group-current-result" role="status" aria-live="polite">
      <div className="capture-group-current-result-heading">
        <CircleCheck aria-hidden="true" size={18} strokeWidth={1.9} />
        <strong>人工复核已通过，事件清洗通过</strong>
      </div>
    </div>
  )
}
