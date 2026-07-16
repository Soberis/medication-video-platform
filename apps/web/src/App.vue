<script setup lang="ts">
import { RouterLink, RouterView } from 'vue-router'
</script>

<template>
  <div class="app-shell">
    <header class="app-header">
      <div>
        <p class="app-eyebrow">护士配药视频 · 云端数据管理</p>
        <h1>配药视频数据管理平台</h1>
      </div>
      <nav class="primary-nav" aria-label="主导航">
        <RouterLink to="/sessions">配药操作</RouterLink>
        <RouterLink to="/assets">视频资产</RouterLink>
        <RouterLink to="/uploads">上传排障</RouterLink>
      </nav>
    </header>

    <main id="main-content">
      <RouterView />
    </main>
  </div>
</template>
