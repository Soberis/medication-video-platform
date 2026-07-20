import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      redirect: '/projects',
    },
    {
      path: '/projects',
      name: 'project-list',
      component: () => import('@/views/ProjectListView.vue'),
    },
    {
      path: '/projects/:projectId/sessions',
      name: 'session-list',
      component: () => import('@/views/SessionListView.vue'),
    },
    {
      path: '/projects/:projectId/sessions/:id',
      name: 'session-detail',
      component: () => import('@/views/SessionDetailView.vue'),
    },
    {
      path: '/projects/:projectId/assets',
      name: 'asset-list',
      component: () => import('@/views/AssetListView.vue'),
    },
    {
      path: '/projects/:projectId/assets/:id',
      name: 'asset-detail',
      component: () => import('@/views/AssetDetailView.vue'),
    },
    { path: '/sessions', redirect: '/projects' },
    { path: '/assets', redirect: '/projects' },
    {
      path: '/uploads',
      name: 'upload-progress',
      component: () => import('@/views/UploadProgressView.vue'),
    },
  ],
})

export default router
