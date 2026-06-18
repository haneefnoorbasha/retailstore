import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { ShoppingBag } from 'lucide-react'
import { Link } from 'react-router-dom'
import { experienceService } from '@/services/experienceService'
import { cartService } from '@/services/cartService'
import { useAppStore } from '@/store/useAppStore'
import { ProductCard } from '@/components/catalog/ProductCard'
import { LoadingSpinner } from '@/components/common/LoadingSpinner'
import { EmptyState } from '@/components/common/EmptyState'
import type { FeaturedProduct } from '@/types'

export function HomePage() {
  const { customerId, setCartItemCount } = useAppStore()
  const [addingId, setAddingId] = useState<string | null>(null)
  const [selectedTag, setSelectedTag] = useState<string | null>(null)
  const queryClient = useQueryClient()

  const { data, isLoading } = useQuery({
    queryKey: ['homepage', customerId],
    queryFn: () => experienceService.getHomepage(customerId, 12),
    staleTime: 2 * 60 * 1000,
  })

  const addToCart = useMutation({
    mutationFn: (p: FeaturedProduct) =>
      cartService.addItem(customerId, {
        productId: p.id, productName: p.name,
        quantity: 1, unitPrice: p.price,
      }),
    onSuccess: cart => {
      setCartItemCount(cart.totalItemCount)
      queryClient.invalidateQueries({ queryKey: ['cart', customerId] })
    },
  })

  const handleAddToCart = async (p: FeaturedProduct) => {
    setAddingId(p.id)
    await addToCart.mutateAsync(p).catch(() => {})
    setAddingId(null)
  }

  const filteredProducts = selectedTag
    ? data?.featuredProducts.filter(p => p.tagNames.includes(selectedTag)) ?? []
    : data?.featuredProducts ?? []

  if (isLoading) return <LoadingSpinner size="lg" />

  return (
    <div>
      <div className="mb-8">
        <h1 className="text-3xl font-bold text-gray-900 mb-2">Welcome to RetailStore</h1>
        <p className="text-gray-500">Curated products, delivered fast.</p>
      </div>

      {/* Tag filters */}
      {data && (
        <div className="flex gap-2 flex-wrap mb-6">
          <button
            onClick={() => setSelectedTag(null)}
            className={`px-4 py-1.5 rounded-full text-sm font-medium transition-colors ${
              !selectedTag ? 'bg-blue-600 text-white' : 'bg-white border border-gray-300 text-gray-600 hover:border-blue-400'
            }`}>
            All
          </button>
          {data.availableTags.map(tag => (
            <button key={tag.name}
              onClick={() => setSelectedTag(selectedTag === tag.name ? null : tag.name)}
              className={`px-4 py-1.5 rounded-full text-sm font-medium transition-colors ${
                selectedTag === tag.name ? 'bg-blue-600 text-white' : 'bg-white border border-gray-300 text-gray-600 hover:border-blue-400'
              }`}>
              {tag.displayName}
            </button>
          ))}
        </div>
      )}

      {filteredProducts.length === 0 ? (
        <EmptyState icon={<ShoppingBag className="w-16 h-16" />}
          title="No products" description="Try a different filter."
          action={<Link to="/catalog" className="text-blue-600 underline">Browse all</Link>} />
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-6">
          {filteredProducts.map(product => (
            <ProductCard key={product.id} product={product}
              onAddToCart={handleAddToCart}
              isAdding={addingId === product.id} />
          ))}
        </div>
      )}
    </div>
  )
}
