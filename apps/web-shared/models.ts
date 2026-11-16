export interface Actor {
  id: number
  loginName: string
  displayName: string
  role: 'BUYER' | 'ADMIN'
}
export interface Account extends Actor {
  enabled: boolean
}
export interface Login {
  token: string
  expiresAt: string
  user: Account
}
export interface Page<T> {
  items: T[]
  total: number
  page: number
  pageSize: number
}
export interface Category {
  id: number
  parentId: number | null
  code: string
  name: string
  inputView: 'SCENT' | 'REFILL' | 'TOOL' | 'GIFT' | null
  sort: number
  enabled: boolean
}
export interface CategoryTree {
  id: number
  code: string
  name: string
  children: Category[]
}
export interface Family {
  id: number
  code: string
  name: string
  description: string
  sort: number
}
export interface Product {
  id: number
  categoryId: number
  familyId: number | null
  categoryName: string
  familyName: string | null
  inputView: 'SCENT' | 'REFILL' | 'TOOL' | 'GIFT'
  name: string
  subtitle: string
  spec: string
  unit: string
  priceCents: number
  stock: number
  active: boolean
  version: number
  imagePath: string
  scentImagePath: string | null
  sceneImagePath: string | null
  description: string
  usageNotes: string
  material: string
  powerInfo: string
  contents: string
  createdAt: string
  updatedAt: string
}
export interface ProductDetail {
  product: Product
  compatibleProducts: Product[]
}
export interface HomeCatalog {
  hero: { key: string; product: Product }[]
  gifts: Product[]
  fragrances: Product[]
  objects: Product[]
}
export type ProductInput = Pick<
  Product,
  | 'categoryId'
  | 'familyId'
  | 'name'
  | 'subtitle'
  | 'spec'
  | 'unit'
  | 'priceCents'
  | 'active'
  | 'imagePath'
  | 'description'
  | 'usageNotes'
  | 'material'
  | 'powerInfo'
  | 'contents'
> & {
  version: number | null
  initialStock: number | null
  compatibleIds: number[]
  extraImages?: { scentImagePath: string | null; sceneImagePath: string | null }
}
export interface Address {
  id: number
  receiver: string
  phone: string
  region: string
  detail: string
  isDefault: boolean
}
export type AddressInput = Omit<Address, 'id'>
export interface CartItem {
  productId: number
  name: string
  spec: string
  unit: string
  imagePath: string
  priceCents: number
  stock: number
  quantity: number
  available: boolean
  subtotalCents: number
}
export interface Cart {
  items: CartItem[]
  totalCents: number
  canCheckout: boolean
}
export interface Order {
  id: number
  orderNo: string
  status: number
  statusName: string
  totalCents: number
  itemCount: number
  createdAt: string
  expiresAt: string
  accountId: number
  receiver: string
  phone: string
  region: string
  addressDetail: string
  carrier: string | null
  trackingNo: string | null
  closeReason: string | null
  paidAt: string | null
  shippedAt: string | null
  completedAt: string | null
  closedAt: string | null
}
export interface OrderItem {
  id: number
  productId: number
  name: string
  spec: string
  unit: string
  priceCents: number
  quantity: number
  imagePath: string
  subtotalCents: number
}
export interface OrderDetail {
  order: Order
  items: OrderItem[]
}
export interface Audit {
  id: number
  actorId: number | null
  action: string
  resourceType: string
  resourceId: number | null
  traceId: string
  summary: string
  createdAt: string
}

export interface Capabilities {
  paymentConfigured: boolean
  advisorConfigured: boolean
}
export interface Payment {
  orderId: number
  status: 'PENDING' | 'SUCCESS' | 'CLOSED'
  tradeNo: string | null
  amountCents: number | null
  successAt: string | null
  needsReview: boolean
}
export type SaleState =
  'REQUESTED' | 'REJECTED' | 'RETURN_REQUIRED' | 'REFUND_PENDING' | 'REFUNDED'
export interface AfterSale {
  id: number
  orderId: number
  orderNo: string
  orderStatus: number
  totalCents: number
  reason: string
  state: SaleState
  reviewNote: string
  returnCarrier: string | null
  returnTrackingNo: string | null
  receivedAt: string | null
  restocked: boolean
  createdAt: string
}
export interface Refund {
  afterSaleId: number
  requestNo: string
  status: 'PENDING' | 'FAILED' | 'SUCCESS'
  amountCents: number
  successAt: string | null
  lastErrorCode: string | null
}
export interface Review {
  id: number
  orderItemId: number
  stars: number
  content: string
  displayName: string
  createdAt: string
}
export interface Settlement {
  payment: Payment | null
  afterSale: AfterSale | null
  refund: Refund | null
  reviews: Review[]
}
export interface Daily {
  date: string
  paidCents: number
  refundedCents: number
  netCents: number
  paymentCount: number
  refundCount: number
}
export interface Finance {
  from: string
  to: string
  days: Daily[]
  paidCents: number
  refundedCents: number
  netCents: number
}
export const saleNames: Record<SaleState, string> = {
  REQUESTED: '待审核',
  REJECTED: '已驳回',
  RETURN_REQUIRED: '待退回商品',
  REFUND_PENDING: '待退款核对',
  REFUNDED: '整单已退款',
}
