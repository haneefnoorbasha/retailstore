// ─── Domain types ─────────────────────────────────────────────────────────────

export interface Tag {
  name: string
  displayName: string
}

export interface Product {
  id: string
  name: string
  description: string
  price: number
  inStock: boolean
  available: boolean
  tags: Tag[]
}

export interface PagedProductResponse {
  products: Product[]
  totalCount: number
  page: number
  size: number
  totalPages: number
  hasNext: boolean
  hasPrevious: boolean
}

export interface CartItem {
  itemId: string
  productId: string
  productName: string
  imageUrl?: string
  quantity: number
  unitPrice: number
  lineTotal: number
}

export interface Cart {
  customerId: string
  items: CartItem[]
  totalItemCount: number
  lineItemCount: number
  subtotal: number
}

export interface CartSummary {
  customerId: string
  items: CartItem[]
  totalItemCount: number
  subtotal: number
  estimatedShipping: number
  estimatedTotal: number
}

export interface OrderLineItem {
  productId: string
  productName: string
  quantity: number
  unitPrice: number
  lineTotal: number
}

export interface ShippingAddress {
  fullName: string
  addressLine1: string
  addressLine2?: string
  city: string
  state: string
  postalCode: string
  country: string
}

export interface Order {
  id: string
  customerId: string
  status: OrderStatus
  lineItems: OrderLineItem[]
  shippingAddress: ShippingAddress
  subtotal: number
  shippingCost: number
  total: number
  cancellable: boolean
  cancellationReason?: string
  createdAt: string
  updatedAt: string
}

export type OrderStatus =
  | 'PENDING'
  | 'CONFIRMED'
  | 'PROCESSING'
  | 'SHIPPED'
  | 'DELIVERED'
  | 'CANCELLED'
  | 'REFUNDED'

// ─── Experience layer (BFF) types ─────────────────────────────────────────────

export interface HomepageResponse {
  featuredProducts: FeaturedProduct[]
  availableTags: Tag[]
  cartItemCount: number
}

export interface FeaturedProduct {
  id: string
  name: string
  description?: string
  price: number
  inStock: boolean
  tagNames: string[]
}

// ─── Request types ─────────────────────────────────────────────────────────────

export interface AddToCartRequest {
  productId: string
  productName: string
  quantity: number
  unitPrice: number
  imageUrl?: string
}

export interface PlaceOrderRequest {
  customerId: string
  checkoutSessionId?: string
  lineItems: PlaceOrderLineItem[]
  shippingAddress: ShippingAddress
  subtotal: number
  shippingCost: number
  total: number
}

export interface PlaceOrderLineItem {
  productId: string
  productName: string
  quantity: number
  unitPrice: number
}
