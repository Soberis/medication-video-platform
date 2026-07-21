<script setup lang="ts">
import {
  ElButton,
  ElCard,
  ElEmpty,
  ElMessage,
  ElMessageBox,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus'
import { onMounted, onUnmounted, ref, shallowRef } from 'vue'

import { CatalogApiError } from '@/api/catalog'
import { createProject, getProjectList, type DataProject } from '@/api/projects'

defineOptions({ name: 'ProjectListView' })

const projects = shallowRef<DataProject[]>([])
const loadState = ref<'loading' | 'success' | 'error'>('loading')
const errorCode = ref('')
const errorMessage = ref('')
const isCreating = ref(false)
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

onMounted(() => void loadProjects())
onUnmounted(() => activeRequest?.abort())
</script>

<template>
  <section class="page-heading" aria-labelledby="projects-title">
    <div>
      <p class="app-eyebrow">数据与权限边界</p>
      <h2 id="projects-title">授权项目</h2>
      <p>选择一个已加入的项目，再查看该项目的配药操作、视频和文件夹。</p>
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

  <ElCard v-if="projects.length > 0" class="table-card" shadow="never">
    <ElTable :data="projects" row-key="project_id">
      <ElTableColumn prop="display_name" label="项目" min-width="220" />
      <ElTableColumn label="我的角色" width="120">
        <template #default="{ row }">
          <ElTag :type="row.current_role === 'ADMIN' ? 'primary' : 'info'">
            {{ row.current_role === 'ADMIN' ? '管理员' : '普通用户' }}
          </ElTag>
        </template>
      </ElTableColumn>
      <ElTableColumn label="状态" width="100">
        <template #default>启用</template>
      </ElTableColumn>
      <ElTableColumn label="操作" width="120" fixed="right">
        <template #default="{ row }">
          <RouterLink
            class="text-link"
            :to="{ name: 'session-list', params: { projectId: row.project_id } }"
          >
            进入项目
          </RouterLink>
        </template>
      </ElTableColumn>
    </ElTable>
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
