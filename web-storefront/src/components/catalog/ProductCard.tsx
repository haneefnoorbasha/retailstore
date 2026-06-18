import { ShoppingCart } from 'lucide-react'
import { Link } from 'react-router-dom'
import type { FeaturedProduct } from '@/types'
import { formatPrice } from '@/utils/format'

interface Props {
  product: FeaturedProduct
  onAddToCart: (product: FeaturedProduct) => void
  isAdding?: boolean
}

export function ProductCard({ product, onAddToCart, isAdding }: Props) {
  return (
    <div className="bg-white rounded-xl border border-gray-100 shadow-sm p-4 flex flex-col hover:shadow-md transition-shadow">
      <div className="bg-gray-100 rounded-lg h-44 mb-4 flex items-center justify-center">
        <span className="text-5xl">🛍️</span>
      </div>
      <div className="flex gap-1 flex-wrap mb-2">
        {product.tagNames.map(t => (
          <span key={t} className="text-xs bg-blue-50 text-blue-700 px-2 py-0.5 rounded-full">{t}</span>
        ))}
      </div>
      <Link to={`/catalog/${product.id}`} className="font-semibold text-gray-900 hover:text-blue-600 mb-1 line-clamp-1">
        {product.name}
      </Link>
      {product.description && (
        <p className="text-sm text-gray-500 line-clamp-2 mb-3 flex-1">{product.description}</p>
      )}
      {!product.inStock && (
        <p className="text-xs text-red-500 mb-2 font-medium">Out of stock</p>
      )}
      <div className="flex items-center justify-between mt-auto pt-3 border-t border-gray-100">
        <span className="text-lg font-bold text-gray-900">{formatPrice(product.price)}</span>
        <button
          onClick={() => onAddToCart(product)}
          disabled={isAdding || !product.inStock}
          className="flex items-center gap-1.5 bg-blue-600 text-white px-3 py-1.5 rounded-lg text-sm font-medium hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
        >
          <ShoppingCart className="w-4 h-4" />
          {isAdding ? 'Adding…' : 'Add to cart'}
        </button>
      </div>
    </div>
  )
}
