import { useEffect } from 'react'
import { Routes, Route } from 'react-router-dom'
import { Layout } from '@/components/layout/Layout'
import { HomePage } from '@/pages/HomePage'
import { CartPage } from '@/pages/CartPage'
import { CheckoutPage } from '@/pages/CheckoutPage'
import { OrderConfirmationPage } from '@/pages/OrderConfirmationPage'
import { OrdersPage } from '@/pages/OrdersPage'
import { AuthCallback } from '@/pages/AuthCallback'
import { authService } from '@/services/authService'
import { useAppStore } from '@/store/useAppStore'

export default function App() {
  const { setUser } = useAppStore()

  // Restore user from session on app load
  useEffect(() => {
    authService.getUser().then(user => {
      if (user && !user.expired) {
        setUser({
          id:    user.profile.sub,
          email: user.profile.email ?? '',
          name:  user.profile.name ?? '',
          role:  (user.profile as any)?.realm_access?.roles?.[0] ?? 'CUSTOMER',
        })
      }
    })
  }, [])

  return (
    <Routes>
      <Route path="/callback" element={<AuthCallback />} />
      <Route
        path="/*"
        element={
          <Layout>
            <Routes>
              <Route path="/"                             element={<HomePage />} />
              <Route path="/catalog"                      element={<HomePage />} />
              <Route path="/cart"                         element={<CartPage />} />
              <Route path="/checkout"                     element={<CheckoutPage />} />
              <Route path="/order-confirmation/:orderId"  element={<OrderConfirmationPage />} />
              <Route path="/orders"                       element={<OrdersPage />} />
            </Routes>
          </Layout>
        }
      />
    </Routes>
  )
}
