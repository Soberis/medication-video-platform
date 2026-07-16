import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      redirect: '/sessions',
    },
    {
      path: '/sessions',
      name: 'session-list',
      component: () => import('@/views/SessionListView.vue'),
    },
    {
      path: '/sessions/:id',
      name: 'session-detail',
      component: () => import('@/views/SessionDetailView.vue'),
    },
    {
      path: '/assets',
      name: 'asset-list',
      component: () => import('@/views/AssetListView.vue'),
    },
    {
      path: '/assets/:id',
      name: 'asset-detail',
      component: () => import('@/views/AssetDetailView.vue'),
    },
    {
      path: '/uploads',
      name: 'upload-progress',
      component: () => import('@/views/UploadProgressView.vue'),
    },
  ],
})

export default router
