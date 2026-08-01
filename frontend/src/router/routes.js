const routes = [
  {
    path: '/',
    component: () => import('layouts/MainLayout.vue'),
    children: [
      { path: '', component: () => import('pages/IndexPage.vue') },
      {
        path: '/bots',
        component: () => import('pages/BotsPage.vue')
      },
      {
        path: '/exchanges',
        component: () => import('pages/ExchangesPage.vue')
      },
      {
        path: '/settings',
        component: () => import('pages/SettingsPage.vue')
      },
    ]
  },

  // Always leave this as last one,
  // but you can also remove it
  {
    path: '/:catchAll(.*)*',
    component: () => import('pages/ErrorNotFound.vue')
  }
]

export default routes
