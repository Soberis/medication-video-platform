<script setup lang="ts">
import { ElAlert, ElButton, ElCard, ElEmpty, ElOption, ElProgress, ElSelect, ElTag } from 'element-plus'
import { computed, onMounted, onUnmounted, ref, shallowRef, watch } from 'vue'

import {
  LocalUploadApiError,
  type LocalUploadPhase,
  type LocalUploadProgress,
  uploadPlatformLocalVideo,
} from '@/api/local-upload'
import { CatalogApiError } from '@/api/catalog'
import { listAssetFolders, listProjects, type AssetFolder, type DataProject } from '@/api/projects'

defineOptions({ name: 'LocalUploadView' })

type TaskStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED'

interface UploadTask {
  readonly id: number
  readonly file: File
  status: TaskStatus
  progress: LocalUploadProgress | null
  errorCode: string | null
  errorMessage: string | null
  captureSessionId: string | null
}

const phaseLabels: Record<LocalUploadPhase, string> = {
  HASHING: '正在计算文件摘要',
  INITIALIZING: '正在创建上传批次',
  REGISTERING: '正在登记视频文件',
  UPLOADING: '正在上传分片',
  VERIFYING: '服务器正在校验视频',
  ASSEMBLING: '正在建立单视角配药操作记录',
  COMPLETED: '上传完成',
}

const projects = shallowRef<DataProject[]>([])
const folders = shallowRef<AssetFolder[]>([])
const selectedProjectId = ref('')
const selectedFolderId = ref<string | null>(null)
const selectedFiles = shallowRef<File[]>([])
const tasks = ref<UploadTask[]>([])
const projectLoadState = ref<'loading' | 'success' | 'error'>('loading')
const folderLoadState = ref<'idle' | 'loading' | 'success' | 'error'>('idle')
const uploadState = ref<'idle' | 'uploading' | 'completed'>('idle')
const errorCode = ref('')
const errorMessage = ref('')
let activeRequest: AbortController | null = null

const isUploading = computed(() => uploadState.value === 'uploading')
const canStart = computed(
  () =>
    !isUploading.value &&
    projectLoadState.value === 'success' &&
    selectedProjectId.value.length > 0 &&
    selectedFiles.value.length > 0,
)
const completedTaskCount = computed(() => tasks.value.filter((task) => task.status === 'COMPLETED').length)

async function loadProjects() {
  projectLoadState.value = 'loading'
  errorCode.value = ''
  errorMessage.value = ''
  try {
    projects.value = await listProjects()
    if (!projects.value.some((project) => project.project_id === selectedProjectId.value)) {
      selectedProjectId.value = ''
    }
    projectLoadState.value = 'success'
  } catch (error) {
    projects.value = []
    selectedProjectId.value = ''
    projectLoadState.value = 'error'
    showError(error, 'PROJECT_REQUEST_FAILED', '暂时无法读取授权项目，请稍后重试。')
  }
}

async function loadFolders(projectId: string) {
  activeRequest?.abort()
  selectedFolderId.value = null
  folders.value = []
  if (!projectId) {
    folderLoadState.value = 'idle'
    return
  }
  const request = new AbortController()
  activeRequest = request
  folderLoadState.value = 'loading'
  try {
    folders.value = await listAssetFolders(projectId, request.signal)
    if (activeRequest === request) folderLoadState.value = 'success'
  } catch (error) {
    if (request.signal.aborted || activeRequest !== request) return
    folderLoadState.value = 'error'
    showError(error, 'ASSET_FOLDER_REQUEST_FAILED', '暂时无法读取项目文件夹，请稍后重试。')
  } finally {
    if (activeRequest === request) activeRequest = null
  }
}

function selectFiles(event: Event) {
  const input = event.target as HTMLInputElement
  selectedFiles.value = Array.from(input.files ?? [])
  input.value = ''
  tasks.value = []
  errorCode.value = ''
  errorMessage.value = ''
}

async function startUpload() {
  if (!canStart.value) return
  const files = selectedFiles.value
  const projectId = selectedProjectId.value
  const folderId = selectedFolderId.value
  tasks.value = files.map((file, index) => ({
    id: index,
    file,
    status: 'QUEUED',
    progress: null,
    errorCode: null,
    errorMessage: null,
    captureSessionId: null,
  }))
  uploadState.value = 'uploading'

  for (const task of tasks.value) {
    task.status = 'RUNNING'
    try {
      const result = await uploadPlatformLocalVideo(
        { projectId, assetFolderId: folderId, file: task.file },
        (progress) => {
          task.progress = progress
        },
      )
      task.captureSessionId = result.captureSessionId
      task.status = 'COMPLETED'
    } catch (error) {
      task.status = 'FAILED'
      if (error instanceof LocalUploadApiError) {
        task.errorCode = error.errorCode
        task.errorMessage = error.message
      } else {
        task.errorCode = 'LOCAL_UPLOAD_FAILED'
        task.errorMessage = '本地视频上传未完成，请检查网络和本地服务后重试。'
      }
    }
  }
  uploadState.value = 'completed'
}

function percent(task: UploadTask) {
  const progress = task.progress
  if (!progress || progress.totalBytes < 1) return 0
  if (progress.phase === 'HASHING') return Math.round((progress.processedBytes / progress.totalBytes) * 100)
  return Math.round((progress.uploadedBytes / progress.totalBytes) * 100)
}

function taskDescription(task: UploadTask) {
  if (task.status === 'QUEUED') return '等待前一个视频完成。'
  if (task.status === 'FAILED') return task.errorMessage ?? '上传未完成。'
  if (task.status === 'COMPLETED') return '已完成校验，并建立单视角配药操作记录。'
  return task.progress ? phaseLabels[task.progress.phase] : '正在准备上传。'
}

function formatBytes(value: number) {
  if (value < 1024) return `${value} B`
  const units = ['KiB', 'MiB', 'GiB', 'TiB']
  let amount = value / 1024
  let unit = units[0]
  for (let index = 1; index < units.length && amount >= 1024; index += 1) {
    amount /= 1024
    unit = units[index]
  }
  return `${new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 }).format(amount)} ${unit}`
}

function showError(error: unknown, fallbackCode: string, fallbackMessage: string) {
  errorCode.value = error instanceof CatalogApiError ? error.errorCode : fallbackCode
  errorMessage.value = error instanceof CatalogApiError ? error.message : fallbackMessage
}

watch(selectedProjectId, (projectId) => void loadFolders(projectId))
onMounted(() => void loadProjects())
onUnmounted(() => activeRequest?.abort())
</script>

<template>
  <section class="page-heading" aria-labelledby="local-upload-title">
    <div>
      <p class="app-eyebrow">平台本地上传</p>
      <h1 id="local-upload-title">选择视频并上传</h1>
      <p>每个视频使用独立批次上传、校验并建立单视角配药操作记录；不会从文件名推断多相机关系。</p>
    </div>
    <ElButton :loading="projectLoadState === 'loading'" :disabled="isUploading" @click="loadProjects">刷新项目</ElButton>
  </section>

  <ElAlert
    v-if="errorMessage"
    class="catalog-alert"
    type="error"
    :title="errorMessage"
    :description="errorCode ? `错误码：${errorCode}` : undefined"
    show-icon
    :closable="false"
  />

  <ElCard class="local-upload-card" shadow="never">
    <form class="local-upload-form" @submit.prevent="startUpload">
      <div class="local-upload-field">
        <label for="local-upload-project">所属项目</label>
        <ElSelect
          id="local-upload-project"
          v-model="selectedProjectId"
          :loading="projectLoadState === 'loading'"
          :disabled="isUploading || projectLoadState !== 'success'"
          placeholder="选择已加入的项目"
        >
          <ElOption
            v-for="project in projects"
            :key="project.project_id"
            :label="`${project.display_name}（${project.current_role === 'ADMIN' ? '管理员' : '普通用户'}）`"
            :value="project.project_id"
          />
        </ElSelect>
      </div>

      <div class="local-upload-field">
        <label for="local-upload-folder">项目文件夹（可选）</label>
        <ElSelect
          id="local-upload-folder"
          v-model="selectedFolderId"
          clearable
          :loading="folderLoadState === 'loading'"
          :disabled="isUploading || !selectedProjectId"
          placeholder="不分类"
        >
          <ElOption v-for="folder in folders" :key="folder.folder_id" :label="folder.display_name" :value="folder.folder_id" />
        </ElSelect>
        <p v-if="selectedProjectId && folderLoadState === 'success' && folders.length === 0" class="form-hint">
          当前项目没有文件夹，视频将保持未分类。
        </p>
      </div>

      <div class="local-upload-field local-upload-files-field">
        <label for="local-upload-files">本机视频</label>
        <input
          id="local-upload-files"
          class="local-file-input"
          type="file"
          accept="video/*"
          multiple
          :disabled="isUploading"
          @change="selectFiles"
        />
        <p class="form-hint">可一次选择多个视频；平台会按顺序分别上传，避免把多个视频误组装为同一次拍摄。</p>
      </div>

      <ul v-if="selectedFiles.length > 0 && tasks.length === 0" class="selected-file-list" aria-label="已选择的视频">
        <li v-for="file in selectedFiles" :key="`${file.name}:${file.size}:${file.lastModified}`">
          <strong>{{ file.name }}</strong>
          <span>{{ formatBytes(file.size) }}</span>
        </li>
      </ul>

      <div class="local-upload-actions">
        <ElButton type="primary" native-type="submit" :disabled="!canStart" :loading="isUploading">
          {{ isUploading ? '正在上传…' : `开始上传${selectedFiles.length > 1 ? `（${selectedFiles.length} 个视频）` : ''}` }}
        </ElButton>
        <RouterLink v-if="selectedProjectId" class="text-link" :to="{ name: 'asset-list', params: { projectId: selectedProjectId } }">
          查看项目视频
        </RouterLink>
      </div>
    </form>
  </ElCard>

  <section v-if="tasks.length > 0" class="local-upload-results" aria-labelledby="local-upload-progress-title">
    <div class="card-header-row">
      <div>
        <p class="app-eyebrow">真实上传进度</p>
        <h3 id="local-upload-progress-title">已完成 {{ completedTaskCount }} / {{ tasks.length }} 个视频</h3>
      </div>
      <ElTag :type="uploadState === 'completed' ? 'success' : 'primary'">
        {{ uploadState === 'completed' ? '本轮已结束' : '上传中' }}
      </ElTag>
    </div>

    <article v-for="task in tasks" :key="task.id" class="local-upload-task">
      <div class="file-heading">
        <div>
          <p class="file-kind">{{ task.status === 'COMPLETED' ? '已完成' : task.status === 'FAILED' ? '失败' : '视频上传' }}</p>
          <h4>{{ task.file.name }}</h4>
        </div>
        <ElTag :type="task.status === 'FAILED' ? 'danger' : task.status === 'COMPLETED' ? 'success' : 'primary'">
          {{ task.status === 'QUEUED' ? '等待中' : task.status === 'RUNNING' ? '处理中' : task.status === 'COMPLETED' ? '完成' : '失败' }}
        </ElTag>
      </div>
      <ElProgress :percentage="percent(task)" :status="task.status === 'FAILED' ? 'exception' : task.status === 'COMPLETED' ? 'success' : undefined" />
      <p class="task-description">{{ taskDescription(task) }}</p>
      <p v-if="task.progress?.batchId" class="batch-meta">批次 ID：<code>{{ task.progress.batchId }}</code></p>
      <p v-if="task.captureSessionId" class="batch-meta">配药操作 ID：<code>{{ task.captureSessionId }}</code></p>
      <p v-if="task.errorCode" class="task-error"><strong>{{ task.errorCode }}</strong><span>{{ task.errorMessage }}</span></p>
    </article>
  </section>

  <ElEmpty
    v-if="projectLoadState === 'success' && projects.length === 0"
    description="当前账号尚未加入项目，无法上传视频。请联系管理员添加项目成员。"
  />
</template>
