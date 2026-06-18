import { create } from 'zustand'
import { persist } from 'zustand/middleware'

interface AuthUser {
  id:    string
  email: string
  name:  string
  role:  string
}

interface AppStore {
  user:          AuthUser | null
  customerId:    string
  cartItemCount: number
  setUser:          (user: AuthUser | null) => void
  setCartItemCount: (count: number) => void
}

const GUEST_ID = `guest-${Math.random().toString(36).slice(2, 9)}`

export const useAppStore = create<AppStore>()(
  persist(
    set => ({
      user:          null,
      customerId:    GUEST_ID,
      cartItemCount: 0,
      setUser: user => set({
        user,
        customerId: user?.id ?? GUEST_ID,
      }),
      setCartItemCount: count => set({ cartItemCount: count }),
    }),
    { name: 'retailstore-app' }
  )
)
