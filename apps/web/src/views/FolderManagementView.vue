<script setup lang="ts">
import {
  ElAlert,
  ElButton,
  ElCard,
  ElEmpty,
  ElMessage,
  ElMessageBox,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus'
import { computed, onUnmounted, ref, shallowRef, watch } from 'vue'
import { useRoute } from 'vue-router'

import { CatalogApiError } from '@/api/catalog'
import {
  createAssetFolder,
  deleteAssetFolder,
  getProject,
  listAssetFolders,
  renameAssetFolder,
  type AssetFolder,
  type ProjectRole,
} from '@/api/projects'

defineOptions({ name: 'FolderManagementView' })

const route = useRoute()
const projectId = computed(() => String(route.params.projectId ?? ''))
const folders = shallowRef<AssetFolder[]>([])
const projectRole = ref<ProjectRole | null>(null)
const loadState = ref<'loading' | 'success' | 'error'>('loading')
const errorCode = ref('')
const errorMessage = ref('')
const isMutating = ref(false)
let activeRequest: AbortController | null = null

const canManageFolders = computed(() => projectRole.value === 'ADMIN')

async function loadFolders() {
  activeRequest?.abort()
  const request = new AbortController()
  activeRequest = request
  loadState.value = 'loading'
  errorCode.value = ''
  errorMessage.value = ''
  try {
    const [project, listedFolders] = await Promise.all([
      getProject(projectId.value, request.signal),
      listAssetFolders(projectId.value, request.signal),
    ])
    if (activeRequest !== request) return
    projectRole.value = project.current_role
    folders.value = listedFolders
    loadState.value = 'success'
  } catch (error) {
    if (request.signal.aborted || activeRequest !== request) return
    folders.value = []
    projectRole.value = null
    loadState.value = 'error'
    errorCode.value = error instanceof CatalogApiError ? error.errorCode : 'FOLDER_REQUEST_FAILED'
    errorMessage.value =
      error instanceof CatalogApiError ? error.message : '暂时无法读取项目文件夹，请稍后重试。'
  } finally {
    if (activeRequest === request) activeRequest = null
  }
}

async function createFolder() {
  if (!canManageFolders.value || isMutating.value) return
  try {
    const { value } = await ElMessageBox.prompt('文件夹仅用于当前项目内的一层视频分类。', '新建文件夹', {
      confirmButtonText: '创建文件夹',
      cancelButtonText: '取消',
      inputPlaceholder: '例如：待标注',
      inputPattern: /\S/,
      inputErrorMessage: '请输入文件夹名称。',
    })
    isMutating.value = true
    await createAssetFolder(projectId.value, value.trim())
    ElMessage.success('文件夹已创建。')
    await loadFolders()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    showCommandError(error)
  } finally {
    isMutating.value = false
  }
}

async function renameFolder(folder: AssetFolder) {
  if (!canManageFolders.value || isMutating.value) return
  try {
    const { value } = await ElMessageBox.prompt('修改仅影响当前项目内的分类名称。', '重命名文件夹', {
      confirmButtonText: '保存名称',
      cancelButtonText: '取消',
      inputValue: folder.display_name,
      inputPattern: /\S/,
      inputErrorMessage: '请输入文件夹名称。',
    })
    isMutating.value = true
    await renameAssetFolder(projectId.value, folder.folder_id, value.trim())
    ElMessage.success('文件夹名称已更新。')
    await loadFolders()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    showCommandError(error)
  } finally {
    isMutating.value = false
  }
}

async function removeFolder(folder: AssetFolder) {
  if (!canManageFolders.value || isMutating.value) return
  try {
    await ElMessageBox.confirm(
      `删除“${folder.display_name}”只会取消其中视频的分类，不会删除任何视频或改变项目权限。`,
      '确认删除文件夹',
      { confirmButtonText: '删除文件夹', cancelButtonText: '取消', type: 'warning' },
    )
    isMutating.value = true
    await deleteAssetFolder(projectId.value, folder.folder_id)
    ElMessage.success('文件夹已删除，原分类视频已变为未分类。')
    await loadFolders()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    showCommandError(error)
  } finally {
    isMutating.value = false
  }
}

function showCommandError(error: unknown) {
  const message = error instanceof CatalogApiError ? error.message : '文件夹操作未完成，请稍后重试。'
  ElMessage.error(message)
}

function asAssetFolder(row: unknown): AssetFolder {
  return row as AssetFolder
}

watch(projectId, () => void loadFolders(), { immediate: true })
onUnmounted(() => activeRequest?.abort())
</script>

<template>
  <nav class="breadcrumb" aria-label="面包屑">
    <RouterLink :to="{ name: 'project-list' }">项目</RouterLink>
    <span aria-hidden="true">/</span>
    <span>文件夹</span>
  </nav>

  <section class="page-heading" aria-labelledby="folders-title">
    <div>
      <p class="app-eyebrow">项目内一层分类</p>
      <h2 id="folders-title">视频文件夹</h2>
      <p>文件夹仅用于归类视频，不改变项目权限、对象存储位置或视频本身。</p>
    </div>
    <div class="detail-actions">
      <ElButton :loading="loadState === 'loading'" @click="loadFolders">刷新</ElButton>
      <ElButton v-if="canManageFolders" type="primary" :loading="isMutating" @click="createFolder">
        新建文件夹
      </ElButton>
    </div>
  </section>

  <ElAlert
    v-if="projectRole === 'MEMBER'"
    title="当前为普通用户权限"
    description="你可以按文件夹查找视频；新建、重命名和删除文件夹仅对项目管理员开放。"
    type="info"
    :closable="false"
    show-icon
  />

  <div v-if="loadState === 'loading'" class="catalog-notice" role="status">正在读取项目文件夹…</div>
  <div v-else-if="loadState === 'error'" class="catalog-error" role="alert">
    <strong>{{ errorCode }}</strong>
    <span>{{ errorMessage }}</span>
    <ElButton @click="loadFolders">重试</ElButton>
  </div>

  <ElCard v-else-if="folders.length > 0" class="table-card" shadow="never">
    <ElTable :data="folders" row-key="folder_id">
      <ElTableColumn prop="display_name" label="文件夹" min-width="240" />
      <ElTableColumn label="分类范围" min-width="240">
        <template #default>仅当前项目 · 一层分类</template>
      </ElTableColumn>
      <ElTableColumn label="状态" width="120"><template #default><ElTag type="info">可用</ElTag></template></ElTableColumn>
      <ElTableColumn v-if="canManageFolders" label="操作" width="190" fixed="right">
        <template #default="{ row }">
          <ElButton link type="primary" :disabled="isMutating" @click="renameFolder(asAssetFolder(row))">重命名</ElButton>
          <ElButton link type="danger" :disabled="isMutating" @click="removeFolder(asAssetFolder(row))">删除</ElButton>
        </template>
      </ElTableColumn>
    </ElTable>
  </ElCard>

  <ElEmpty
    v-else-if="loadState === 'success'"
    :description="canManageFolders ? '尚未创建文件夹。可先新建一个分类，再在视频详情中归类。' : '当前项目尚未创建文件夹。'"
  />
</template>
