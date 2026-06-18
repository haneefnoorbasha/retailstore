import { UserManager, WebStorageStateStore, type User } from 'oidc-client-ts'

const KEYCLOAK_BASE = import.meta.env.VITE_KEYCLOAK_URL ?? 'http://localhost:8180'
const KEYCLOAK_REALM = import.meta.env.VITE_KEYCLOAK_REALM ?? 'retailstore'
const APP_URL = import.meta.env.VITE_APP_URL ?? 'http://localhost:3000'

export const userManager = new UserManager({
  authority:             `${KEYCLOAK_BASE}/realms/${KEYCLOAK_REALM}`,
  client_id:             'web-storefront',
  redirect_uri:          `${APP_URL}/callback`,
  silent_redirect_uri:   `${APP_URL}/silent-renew.html`,
  post_logout_redirect_uri: APP_URL,
  response_type:         'code',
  scope:                 'openid profile email roles',
  automaticSilentRenew:  true,
  userStore: new WebStorageStateStore({ store: window.localStorage }),
})

export const authService = {
  login: () => userManager.signinRedirect(),

  logout: () => userManager.signoutRedirect(),

  handleCallback: () => userManager.signinRedirectCallback(),

  getUser: (): Promise<User | null> => userManager.getUser(),

  getAccessToken: async (): Promise<string | null> => {
    const user = await userManager.getUser()
    return user?.access_token ?? null
  },

  isAuthenticated: async (): Promise<boolean> => {
    const user = await userManager.getUser()
    return !!user && !user.expired
  },
}
