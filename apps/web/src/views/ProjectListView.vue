<script setup lang="ts">
import {
  ElButton,
  ElCard,
  ElDropdown,
  ElDropdownItem,
  ElDropdownMenu,
  ElEmpty,
  ElMessage,
  ElMessageBox,
  ElTag,
} from 'element-plus'
import { onMounted, onUnmounted, ref, shallowRef } from 'vue'

import { CatalogApiError } from '@/api/catalog'
import {
  archiveProject,
  createProject,
  getProjectList,
  renameProject,
  type DataProject,
} from '@/api/projects'

defineOptions({ name: 'ProjectListView' })

const projects = shallowRef<DataProject[]>([])
const loadState = ref<'loading' | 'success' | 'error'>('loading')
const errorCode = ref('')
const errorMessage = ref('')
const isCreating = ref(false)
const renamingProjectId = ref<string | null>(null)
const archivingProjectId = ref<string | null>(null)
const canCreateProjects = ref(false)
let activeRequest: AbortController | null = null

async function loadProjects() {
  activeRequest?.abort()
  const request = new AbortController()
  activeRequest = request
  loadState.value = 'loading'
  try {
    const response = await getProjectList(request.signal)
    if (activeRequest !== request) return
    projects.value = response.items
    canCreateProjects.value = response.can_create_projects
    if (activeRequest === request) loadState.value = 'success'
  } catch (error) {
    if (request.signal.aborted || activeRequest !== request) return
    projects.value = []
    canCreateProjects.value = false
    loadState.value = 'error'
    errorCode.value = error instanceof CatalogApiError ? error.errorCode : 'PROJECT_REQUEST_FAILED'
    errorMessage.value =
      error instanceof CatalogApiError ? error.message : '暂时无法读取授权项目，请稍后重试。'
  } finally {
    if (activeRequest === request) activeRequest = null
  }
}

async function createDataProject() {
  if (isCreating.value) return
  try {
    const { value } = await ElMessageBox.prompt(
      '创建后，当前管理员会自动成为该项目的第一位项目管理员。',
      '新建项目',
      {
        confirmButtonText: '创建项目',
        cancelButtonText: '取消',
        inputPlaceholder: '例如：病区 A 合成数据验证',
        inputPattern: /\S/,
        inputErrorMessage: '请输入项目名称。',
      },
    )
    const displayName = value.trim()
    if (displayName.length > 128) {
      ElMessage.error('项目名称不能超过 128 个字符。')
      return
    }
    isCreating.value = true
    await createProject(displayName)
    ElMessage.success('项目已创建，当前身份已获得该项目的管理员权限。')
    await loadProjects()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    const message = error instanceof CatalogApiError ? error.message : '项目创建未完成，请稍后重试。'
    ElMessage.error(message)
  } finally {
    isCreating.value = false
  }
}

async function renameDataProject(project: DataProject) {
  if (renamingProjectId.value) return
  try {
    const { value } = await ElMessageBox.prompt(
      '仅修改项目显示名称，不会影响项目成员、文件夹或视频归属。',
      '修改项目名称',
      {
        confirmButtonText: '保存名称',
        cancelButtonText: '取消',
        inputValue: project.display_name,
        inputPattern: /\S/,
        inputErrorMessage: '请输入项目名称。',
      },
    )
    const displayName = value.trim()
    if (displayName.length > 128) {
      ElMessage.error('项目名称不能超过 128 个字符。')
      return
    }
    renamingProjectId.value = project.project_id
    await renameProject(project.project_id, displayName)
    ElMessage.success('项目名称已更新。')
    await loadProjects()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof CatalogApiError ? error.message : '项目名称未更新，请稍后重试。')
  } finally {
    renamingProjectId.value = null
  }
}

async function archiveDataProject(project: DataProject) {
  if (archivingProjectId.value) return
  try {
    const { value } = await ElMessageBox.prompt(
      '归档会立即停止项目访问并从日常列表移除，但不会物理删除视频、对象或审计记录。请输入完整项目名称确认。',
      '归档项目',
      {
        confirmButtonText: '确认归档',
        cancelButtonText: '取消',
        inputPlaceholder: project.display_name,
        inputPattern: /\S/,
        inputErrorMessage: '请输入项目名称确认。',
      },
    )
    if (value.trim() !== project.display_name) {
      ElMessage.error('输入的项目名称不匹配，未执行归档。')
      return
    }
    archivingProjectId.value = project.project_id
    await archiveProject(project.project_id)
    ElMessage.success('项目已归档，视频与审计记录仍被保留。')
    await loadProjects()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof CatalogApiError ? error.message : '项目未归档，请稍后重试。')
  } finally {
    archivingProjectId.value = null
  }
}

onMounted(() => void loadProjects())
onUnmounted(() => activeRequest?.abort())
</script>

<template>
  <section class="page-heading project-page-heading" aria-labelledby="projects-title">
    <div>
      <h1 id="projects-title">项目</h1>
      <p>选择项目，继续管理配药操作、视频和文件夹。</p>
    </div>
    <div class="detail-actions">
      <ElButton :loading="loadState === 'loading'" @click="loadProjects">刷新</ElButton>
      <ElButton v-if="canCreateProjects" type="primary" :loading="isCreating" @click="createDataProject">
        新建项目
      </ElButton>
    </div>
  </section>

  <div v-if="loadState === 'loading'" class="catalog-notice" role="status">正在读取授权项目…</div>
  <div v-else-if="loadState === 'error'" class="catalog-error" role="alert">
    <strong>{{ errorCode }}</strong>
    <span>{{ errorMessage }}</span>
    <ElButton @click="loadProjects">重试</ElButton>
  </div>

  <ElCard v-if="projects.length > 0" class="project-list-card" shadow="never">
    <ul class="project-list" aria-label="已加入的项目">
      <li v-for="project in projects" :key="project.project_id" class="project-list-item">
        <div class="project-list-name">
          <strong>{{ project.display_name }}</strong>
          <span>我的角色</span>
          <ElTag :type="project.current_role === 'ADMIN' ? 'primary' : 'info'">
            {{ project.current_role === 'ADMIN' ? '管理员' : '普通用户' }}
          </ElTag>
        </div>
        <div class="project-list-actions">
          <RouterLink
            class="text-link"
            :to="{ name: 'session-list', params: { projectId: project.project_id } }"
          >
            进入项目
          </RouterLink>
          <ElDropdown v-if="project.current_role === 'ADMIN'" trigger="click">
            <ElButton link type="primary">管理</ElButton>
            <template #dropdown>
              <ElDropdownMenu>
                <ElDropdownItem :disabled="renamingProjectId === project.project_id" @click="renameDataProject(project)">
                  修改名称
                </ElDropdownItem>
                <ElDropdownItem :disabled="archivingProjectId === project.project_id" divided @click="archiveDataProject(project)">
                  归档项目
                </ElDropdownItem>
              </ElDropdownMenu>
            </template>
          </ElDropdown>
        </div>
      </li>
    </ul>
  </ElCard>

  <ElEmpty
    v-else-if="loadState === 'success'"
    description="当前账号尚未加入任何项目。管理员可新建首个项目；普通用户需要由项目管理员添加为成员。"
  >
    <template v-if="canCreateProjects" #default>
      <ElButton type="primary" :loading="isCreating" @click="createDataProject">新建项目</ElButton>
    </template>
  </ElEmpty>
</template>
