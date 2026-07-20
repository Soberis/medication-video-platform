<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, RouterView } from 'vue-router'
import { useRoute } from 'vue-router'

const route = useRoute()
const projectId = computed(() => String(route.params.projectId ?? ''))
const sessionsRoute = computed(() =>
  projectId.value
    ? { name: 'session-list', params: { projectId: projectId.value } }
    : { name: 'project-list' },
)
const assetsRoute = computed(() =>
  projectId.value
    ? { name: 'asset-list', params: { projectId: projectId.value } }
    : { name: 'project-list' },
)
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
        <RouterLink :to="sessionsRoute">配药操作</RouterLink>
        <RouterLink :to="assetsRoute">视频资产</RouterLink>
        <RouterLink :to="{ name: 'local-upload' }">本地上传</RouterLink>
        <RouterLink to="/uploads">上传排障</RouterLink>
      </nav>
    </header>

    <main id="main-content">
      <RouterView />
    </main>
  </div>
</template>
