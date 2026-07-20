<script setup lang="ts">
import { ElButton, ElCard, ElEmpty, ElTable, ElTableColumn, ElTag } from 'element-plus'
import { onMounted, onUnmounted, ref, shallowRef } from 'vue'

import { CatalogApiError } from '@/api/catalog'
import { listProjects, type DataProject } from '@/api/projects'

defineOptions({ name: 'ProjectListView' })

const projects = shallowRef<DataProject[]>([])
const loadState = ref<'loading' | 'success' | 'error'>('loading')
const errorCode = ref('')
const errorMessage = ref('')
let activeRequest: AbortController | null = null

async function loadProjects() {
  activeRequest?.abort()
  const request = new AbortController()
  activeRequest = request
  loadState.value = 'loading'
  try {
    projects.value = await listProjects(request.signal)
    if (activeRequest === request) loadState.value = 'success'
  } catch (error) {
    if (request.signal.aborted || activeRequest !== request) return
    projects.value = []
    loadState.value = 'error'
    errorCode.value = error instanceof CatalogApiError ? error.errorCode : 'PROJECT_REQUEST_FAILED'
    errorMessage.value =
      error instanceof CatalogApiError ? error.message : '暂时无法读取授权项目，请稍后重试。'
  } finally {
    if (activeRequest === request) activeRequest = null
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
    <ElButton :loading="loadState === 'loading'" @click="loadProjects">刷新</ElButton>
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
    description="当前账号尚未加入任何项目，请联系管理员添加项目成员。"
  />
</template>
