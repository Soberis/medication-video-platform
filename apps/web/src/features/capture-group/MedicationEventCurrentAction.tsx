import { Button } from 'antd'
import { CircleAlert, CircleCheck, Clock3 } from 'lucide-react'
import type { Ref } from 'react'

import { Section } from '@/components/workspace/Section'
import { StatusIndicator } from '@/components/status/StatusIndicator'
import {
  medicationEventWorkflowStageLabels,
  type MedicationEventWorkflowModel,
  type MedicationEventWorkflowPrimaryAction,
  type MedicationEventWorkflowStep,
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

function actionMessage(workflow: MedicationEventWorkflowModel, primaryActionHasForm: boolean) {
  if (workflow.blocker) return workflow.blocker
  if (workflow.waitingReason) return workflow.waitingReason
  if (primaryActionHasForm && workflow.primaryAction) return '在下方直接完成当前动作。'
  if (workflow.primaryAction) return '根据当前服务端事实完成这一项处理。'
  return workflow.stage === 'TRAINING_ELIGIBILITY'
    ? '当前配药事件工作流已完成。'
    : '当前没有可执行的处理动作。'
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
      className="capture-group-current-action"
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
      {!isCompleted ? (
        <ol className="capture-group-current-action-steps" aria-label="配药事件处理流程">
          {workflow.steps.map((step) => (
            <WorkflowStep key={step.stage} step={step} currentStage={workflow.stage} />
          ))}
        </ol>
      ) : null}
    </Section>
  )
}

function CompletedResultSummary() {
  return (
    <div className="capture-group-current-result" role="status" aria-live="polite">
      <div className="capture-group-current-result-heading">
        <CircleCheck aria-hidden="true" size={18} strokeWidth={1.9} />
        <strong>流程已完成</strong>
      </div>
      <ul className="capture-group-current-result-facts" aria-label="配药事件完成结果">
        <li>人工复核：已通过</li>
        <li>训练资格检查：已通过</li>
      </ul>
      <a className="capture-group-current-result-link" href="#capture-group-review-cleaning">
        查看复核与训练资格详情 →
      </a>
    </div>
  )
}

function WorkflowStep({
  step,
  currentStage,
}: {
  step: MedicationEventWorkflowStep
  currentStage: MedicationEventWorkflowModel['stage']
}) {
  return (
    <li
      className={`capture-group-current-action-step is-${step.state}`}
      data-state={step.state}
      aria-current={step.stage === currentStage ? 'step' : undefined}
    >
      <span className="capture-group-current-action-step-icon">{stepStateIcon(step.state)}</span>
      <span className="capture-group-current-action-step-copy">
        <strong>{step.label}</strong>
        <small>{stepStateLabels[step.state]}</small>
      </span>
    </li>
  )
}
