import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from 'react-router-dom'
import { Trash2, ShoppingBag } from 'lucide-react'
import { cartService } from '@/services/cartService'
import { useAppStore } from '@/store/useAppStore'
import { formatPrice } from '@/utils/format'
import { LoadingSpinner } from '@/components/common/LoadingSpinner'
import { EmptyState } from '@/components/common/EmptyState'

const SHIPPING = 5.99

export function CartPage() {
  const { customerId, setCartItemCount } = useAppStore()
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const { data: cart, isLoading } = useQuery({
    queryKey: ['cart', customerId],
    queryFn: () => cartService.getCart(customerId),
  })

  const removeItem = useMutation({
    mutationFn: (itemId: string) => cartService.removeItem(customerId, itemId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['cart', customerId] }),
  })

  const updateQty = useMutation({
    mutationFn: ({ itemId, qty }: { itemId: string; qty: number }) =>
      cartService.updateItem(customerId, itemId, qty),
    onSuccess: updated => {
      setCartItemCount(updated.totalItemCount)
      queryClient.setQueryData(['cart', customerId], updated)
    },
  })

  if (isLoading) return <LoadingSpinner />
  const items = cart?.items ?? []

  if (items.length === 0) {
    return (
      <EmptyState icon={<ShoppingBag className="w-16 h-16" />}
        title="Your cart is empty" description="Add some products to get started."
        action={<Link to="/" className="bg-blue-600 text-white px-6 py-2 rounded-lg hover:bg-blue-700">Browse Products</Link>} />
    )
  }

  const subtotal = cart?.subtotal ?? 0
  const total = subtotal + SHIPPING

  return (
    <div className="max-w-3xl mx-auto">
      <h1 className="text-2xl font-bold mb-6">Shopping Cart ({cart?.totalItemCount} items)</h1>
      <div className="space-y-3 mb-6">
        {items.map(item => (
          <div key={item.itemId} className="bg-white rounded-xl border border-gray-100 p-4 flex items-center gap-4">
            <div className="text-3xl flex-shrink-0">🛍️</div>
            <div className="flex-1 min-w-0">
              <p className="font-semibold text-gray-900 truncate">{item.productName}</p>
              <p className="text-sm text-gray-500">{formatPrice(item.unitPrice)} each</p>
            </div>
            <div className="flex items-center gap-2 flex-shrink-0">
              <button onClick={() => updateQty.mutate({ itemId: item.itemId, qty: item.quantity - 1 })}
                className="w-8 h-8 rounded-full border border-gray-300 flex items-center justify-center hover:bg-gray-50 font-medium">−</button>
              <span className="w-8 text-center font-semibold">{item.quantity}</span>
              <button onClick={() => updateQty.mutate({ itemId: item.itemId, qty: item.quantity + 1 })}
                className="w-8 h-8 rounded-full border border-gray-300 flex items-center justify-center hover:bg-gray-50 font-medium">+</button>
            </div>
            <div className="w-20 text-right font-semibold flex-shrink-0">{formatPrice(item.lineTotal)}</div>
            <button onClick={() => removeItem.mutate(item.itemId)} className="text-red-400 hover:text-red-600 flex-shrink-0">
              <Trash2 className="w-4 h-4" />
            </button>
          </div>
        ))}
      </div>

      <div className="bg-white rounded-xl border border-gray-100 p-6">
        <div className="space-y-2 mb-4">
          <div className="flex justify-between text-sm text-gray-600"><span>Subtotal</span><span>{formatPrice(subtotal)}</span></div>
          <div className="flex justify-between text-sm text-gray-600"><span>Shipping</span><span>{formatPrice(SHIPPING)}</span></div>
          <div className="flex justify-between font-bold text-lg pt-2 border-t border-gray-100"><span>Total</span><span>{formatPrice(total)}</span></div>
        </div>
        <button onClick={() => navigate('/checkout')}
          className="w-full bg-blue-600 text-white py-3 rounded-lg font-semibold hover:bg-blue-700 transition-colors">
          Proceed to Checkout →
        </button>
      </div>
    </div>
  )
}
