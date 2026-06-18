import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { cartService } from '@/services/cartService'
import { orderService } from '@/services/orderService'
import { useAppStore } from '@/store/useAppStore'
import { formatPrice } from '@/utils/format'
import { LoadingSpinner } from '@/components/common/LoadingSpinner'
import type { ShippingAddress } from '@/types'

const SHIPPING = 5.99
const EMPTY: ShippingAddress = {
  fullName: '', addressLine1: '', addressLine2: '',
  city: '', state: '', postalCode: '', country: 'US',
}

export function CheckoutPage() {
  const { customerId, setCartItemCount } = useAppStore()
  const navigate = useNavigate()
  const [addr, setAddr] = useState<ShippingAddress>(EMPTY)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')

  const { data: cart, isLoading } = useQuery({
    queryKey: ['cart', customerId],
    queryFn: () => cartService.getCart(customerId),
  })

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!cart || cart.items.length === 0) return
    setSubmitting(true); setError('')
    try {
      const subtotal = cart.subtotal
      const order = await orderService.placeOrder({
        customerId,
        lineItems: cart.items.map(i => ({
          productId: i.productId, productName: i.productName,
          quantity: i.quantity, unitPrice: i.unitPrice,
        })),
        shippingAddress: addr,
        subtotal,
        shippingCost: SHIPPING,
        total: subtotal + SHIPPING,
      })
      await cartService.clearCart(customerId)
      setCartItemCount(0)
      navigate(`/order-confirmation/${order.id}`)
    } catch {
      setError('Failed to place order. Please try again.')
    } finally {
      setSubmitting(false)
    }
  }

  if (isLoading) return <LoadingSpinner />

  const subtotal = cart?.subtotal ?? 0
  const total = subtotal + SHIPPING

  const field = (key: keyof ShippingAddress, label: string, required = true) => (
    <div key={key}>
      <label className="block text-sm font-medium text-gray-700 mb-1">{label}</label>
      <input type="text" required={required} value={addr[key] ?? ''}
        onChange={e => setAddr(a => ({ ...a, [key]: e.target.value }))}
        className="w-full border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500 text-sm" />
    </div>
  )

  return (
    <div className="max-w-5xl mx-auto grid grid-cols-1 lg:grid-cols-2 gap-8">
      <div>
        <h1 className="text-2xl font-bold mb-6">Shipping Details</h1>
        {error && <div className="bg-red-50 border border-red-200 text-red-700 rounded-lg p-3 mb-4 text-sm">{error}</div>}
        <form onSubmit={handleSubmit} className="space-y-4">
          {field('fullName', 'Full Name')}
          {field('addressLine1', 'Address Line 1')}
          {field('addressLine2', 'Address Line 2 (optional)', false)}
          <div className="grid grid-cols-2 gap-4">
            {field('city', 'City')}
            {field('state', 'State')}
          </div>
          <div className="grid grid-cols-2 gap-4">
            {field('postalCode', 'Postal Code')}
            {field('country', 'Country')}
          </div>
          <button type="submit" disabled={submitting}
            className="w-full bg-blue-600 text-white py-3 rounded-lg font-semibold hover:bg-blue-700 disabled:opacity-50 mt-2 transition-colors">
            {submitting ? 'Placing order…' : `Place Order — ${formatPrice(total)}`}
          </button>
        </form>
      </div>
      <div>
        <h2 className="text-xl font-bold mb-4">Order Summary</h2>
        <div className="bg-white rounded-xl border border-gray-100 p-6 space-y-3">
          {cart?.items.map(item => (
            <div key={item.itemId} className="flex justify-between text-sm">
              <span className="text-gray-700">{item.productName} × {item.quantity}</span>
              <span className="font-medium">{formatPrice(item.lineTotal)}</span>
            </div>
          ))}
          <div className="border-t border-gray-100 pt-3 space-y-2">
            <div className="flex justify-between text-sm text-gray-500"><span>Subtotal</span><span>{formatPrice(subtotal)}</span></div>
            <div className="flex justify-between text-sm text-gray-500"><span>Shipping</span><span>{formatPrice(SHIPPING)}</span></div>
            <div className="flex justify-between font-bold text-lg pt-2 border-t border-gray-100"><span>Total</span><span>{formatPrice(total)}</span></div>
          </div>
        </div>
      </div>
    </div>
  )
}
