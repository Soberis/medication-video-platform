<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, RouterView } from 'vue-router'
import { useRoute } from 'vue-router'

const route = useRoute()
const projectId = computed(() => String(route.params.projectId ?? ''))
const sessionsRoute = computed(() => ({ name: 'session-list', params: { projectId: projectId.value } }))
const assetsRoute = computed(() => ({ name: 'asset-list', params: { projectId: projectId.value } }))
const foldersRoute = computed(() => ({ name: 'folder-management', params: { projectId: projectId.value } }))
</script>

<template>
  <div class="app-shell">
    <header class="app-header">
      <div>
        <p class="app-eyebrow">护士配药视频 · 云端数据管理</p>
        <h1>配药视频数据管理平台</h1>
      </div>
      <nav class="primary-nav" aria-label="主导航">
        <RouterLink :to="{ name: 'project-list' }">项目</RouterLink>
        <RouterLink v-if="projectId" :to="sessionsRoute">配药操作</RouterLink>
        <span v-else class="nav-unavailable" title="请先选择项目">配药操作</span>
        <RouterLink v-if="projectId" :to="assetsRoute">视频资产</RouterLink>
        <span v-else class="nav-unavailable" title="请先选择项目">视频资产</span>
        <RouterLink v-if="projectId" :to="foldersRoute">文件夹</RouterLink>
        <span v-else class="nav-unavailable" title="请先选择项目">文件夹</span>
        <RouterLink :to="{ name: 'local-upload' }">本地上传</RouterLink>
        <RouterLink to="/uploads">上传排障</RouterLink>
      </nav>
    </header>

    <main id="main-content">
      <RouterView />
    </main>
  </div>
</template>
