import { Link } from 'react-router-dom'
import { ShoppingCart, Package, LogIn, LogOut } from 'lucide-react'
import { useAppStore } from '@/store/useAppStore'
import { authService } from '@/services/authService'

export function Navbar() {
  const cartItemCount = useAppStore(s => s.cartItemCount)
  const user = useAppStore(s => s.user)
  const { setUser } = useAppStore()

  const handleLogout = () => {
    setUser(null)
    authService.logout()
  }

  return (
    <nav className="sticky top-0 z-50 bg-white border-b border-gray-200 shadow-sm">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 flex items-center justify-between h-16">
        <Link to="/" className="flex items-center gap-2 font-bold text-xl text-blue-700">
          <Package className="w-6 h-6" /> RetailStore
        </Link>
        <div className="hidden sm:flex items-center gap-8">
          <Link to="/" className="text-sm text-gray-600 hover:text-blue-600">Home</Link>
          <Link to="/catalog" className="text-sm text-gray-600 hover:text-blue-600">Shop</Link>
          <Link to="/orders" className="text-sm text-gray-600 hover:text-blue-600">My Orders</Link>
        </div>
        <div className="flex items-center gap-4">
          <Link to="/cart" className="relative">
            <ShoppingCart className="w-6 h-6 text-gray-600 hover:text-blue-600" />
            {cartItemCount > 0 && (
              <span className="absolute -top-2 -right-2 bg-blue-600 text-white text-xs rounded-full min-w-[18px] h-[18px] flex items-center justify-center font-semibold px-1">
                {cartItemCount > 99 ? '99+' : cartItemCount}
              </span>
            )}
          </Link>
          {user ? (
            <div className="flex items-center gap-3">
              <span className="text-sm text-gray-700 hidden sm:block">{user.name || user.email}</span>
              <button
                onClick={handleLogout}
                className="flex items-center gap-1 text-sm text-gray-600 hover:text-red-600"
                title="Sign out"
              >
                <LogOut className="w-5 h-5" />
              </button>
            </div>
          ) : (
            <button
              onClick={() => authService.login()}
              className="flex items-center gap-1 text-sm text-gray-600 hover:text-blue-600"
              title="Sign in"
            >
              <LogIn className="w-5 h-5" />
              <span className="hidden sm:block">Sign in</span>
            </button>
          )}
        </div>
      </div>
    </nav>
  )
}
