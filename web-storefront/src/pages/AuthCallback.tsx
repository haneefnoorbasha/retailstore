import { useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { authService } from '@/services/authService'
import { useAppStore } from '@/store/useAppStore'
import { LoadingSpinner } from '@/components/common/LoadingSpinner'

export function AuthCallback() {
  const navigate = useNavigate()
  const { setUser } = useAppStore()

  useEffect(() => {
    authService.handleCallback()
      .then(user => {
        setUser({
          id:    user.profile.sub,
          email: user.profile.email ?? '',
          name:  user.profile.name ?? '',
          role:  (user.profile as any)?.realm_access?.roles?.[0] ?? 'CUSTOMER',
        })
        navigate('/', { replace: true })
      })
      .catch(() => navigate('/', { replace: true }))
  }, [])

  return (
    <div className="flex items-center justify-center min-h-screen">
      <LoadingSpinner />
    </div>
  )
}
