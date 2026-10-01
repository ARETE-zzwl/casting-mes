export type RouteType = "MID_TEMP_WAX" | "LOW_TEMP_WAX" | "SAND_OUTSOURCE";
export type OrderStatus = "DRAFT" | "SUBMITTED" | "APPROVED" | "RELEASED";
export type OrderPriority = "SAMPLE" | "NORMAL" | "URGENT";
export type TaskStatus = "BLOCKED" | "READY" | "ASSIGNED" | "IN_PROGRESS" | "COMPLETED";
export type TaskReportingMode =
  | "SELF_REPORTED_QUANTITY"
  | "FIXED_QUANTITY"
  | "HANDOFF_TO_TREE"
  | "HANDOFF_TO_NEXT"
  | "TREE_COUNT";
export type CompensationMode = "PIECE_PCS" | "PIECE_TREE" | "HOURLY" | "PIECE_KG" | "HANDOFF_ONLY";
export type SettlementUnit = "PCS" | "TREE" | "KG";

export interface Customer {
  id: string;
  code: string;
  name: string;
  contactName: string | null;
  contactPhone: string | null;
  salesOwner: string | null;
  active: boolean;
  createdAt: string;
}

export interface CustomerSensitiveRequest {
  id: string;
  requestNo: string;
  actionType: "CREATE_CUSTOMER";
  proposedCode: string | null;
  proposedName: string;
  proposedContactName: string | null;
  proposedContactPhone: string | null;
  proposedSalesOwner: string | null;
  requestNote: string | null;
  requestedBy: string;
  requestedAt: string;
  status: "PENDING" | "APPROVED" | "REJECTED";
  reviewedBy: string | null;
  reviewedAt: string | null;
  reviewNote: string | null;
  executedCustomerId: string | null;
  executedAt: string | null;
}

export interface Product {
  id: string;
  code: string;
  name: string;
  routeType: RouteType;
  routeVersion: string;
  modelImageUrl: string | null;
	specification: string | null;
	material: string | null;
  active: boolean;
  createdAt: string;
}

export interface ProductMoldRelation {
  id: string;
  productId: string;
  productCode: string;
  productName: string;
  moldAssetId: string;
  moldAssetCode: string;
  moldAssetName: string;
  moldStatus: string;
  locationCode: string | null;
  ownershipType: "COMPANY_OWNED" | "CUSTOMER_OWNED";
  ownerName: string | null;
  moldImageUrl: string | null;
  note: string | null;
  createdBy: string;
  createdAt: string;
}

export interface CustomerProductMoldRelation {
  id: string;
  customerId: string;
  customerCode: string;
  customerName: string;
  productId: string;
  productCode: string;
  productName: string;
  moldAssetId: string;
  moldAssetCode: string;
  moldAssetName: string;
  moldStatus: string;
  moldCustodyStatus: string | null;
  locationCode: string | null;
  ownershipType: "COMPANY_OWNED" | "CUSTOMER_OWNED";
  ownerName: string | null;
  moldImageUrl: string | null;
  createdBy: string;
  createdAt: string;
  lastUsedAt: string;
}

export interface CustomerProductOrderHistory {
  customerId: string;
  customerCode: string;
  customerName: string;
  productId: string;
  productCode: string;
  productName: string;
  routeType: RouteType;
  orderCount: number;
  moldCount: number;
  lastOrderAt: string;
  lastMoldUsedAt: string | null;
}

export interface MoldStorageLocation {
  locationCode: string;
  locationName: string;
  capacity: number;
  active: boolean;
  occupiedCount: number;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

export interface OrderLine {
  id: string;
  lineNo: number;
  productId: string;
  productCode: string;
  productName: string;
  routeType: RouteType;
  routeVersion: string;
  modelImageUrl: string | null;
	productMaterial: string | null;
  orderedQuantity: number;
  unit: string;
	salesUnitPrice: number | null;
	salesPriceUnit: "TON" | "KG" | "PCS" | "SET" | "EA" | null;
	processCardVersion: string | null;
	engineeringParameters: string | null;
	engineeringOperationParameters: string | null;
	engineeringConfirmedBy: string | null;
	engineeringConfirmedAt: string | null;
	defaultProcessCardVersion: string | null;
	defaultProcessReleasedBy: string | null;
	defaultProcessReleasedAt: string | null;
}

export interface Order {
  id: string;
  orderNo: string;
  customerId: string;
  customerCode: string;
  customerName: string;
  status: OrderStatus;
  priority: OrderPriority;
  routeType: RouteType;
  requestedDeliveryDate: string | null;
	remark: string | null;
	orderDrawingUrl: string | null;
	contractAttachmentUrl: string | null;
	createdBy: string | null;
	processCardVersion: string | null;
	engineeringParameters: string | null;
	engineeringOperationParameters: string | null;
	engineeringConfirmedBy: string | null;
	engineeringConfirmedAt: string | null;
	engineeringReturnedBy: string | null;
	engineeringReturnedAt: string | null;
	engineeringReturnReason: string | null;
	engineeringReturnResolvedAt: string | null;
	defaultProcessCardVersion: string | null;
	defaultProcessReleasedBy: string | null;
	defaultProcessReleasedAt: string | null;
  lines: OrderLine[];
  createdAt: string;
	approvedAt: string | null;
	releasedAt: string | null;
	customerManagerCode: string | null;
	customerManagerReviewedAt: string | null;
	customerManagerReviewNote: string | null;
	customerManagerReturnedBy: string | null;
	customerManagerReturnedAt: string | null;
	customerManagerReturnReason: string | null;
	customerManagerReturnResolvedAt: string | null;
	generalManagerCode: string | null;
	generalManagerReviewedAt: string | null;
	generalManagerReviewNote: string | null;
	generalManagerReturnedBy: string | null;
	generalManagerReturnedAt: string | null;
	generalManagerReturnReason: string | null;
	generalManagerReturnResolvedAt: string | null;
}

export interface SalesPerformance {
  period: string;
  scope: "SELF" | "ALL";
  generatedAt: string;
  overview: {
    recognizedAmount: number;
    reviewedOrders: number;
    reviewedCustomers: number;
    averageOrderAmount: number;
    pendingReviewOrders: number;
    excludedLineCount: number;
  };
  ownerRanking: Array<{
    salesOwner: string;
    reviewedOrders: number;
    customerCount: number;
    recognizedAmount: number;
    pendingReviewOrders: number;
    excludedLineCount: number;
  }>;
  monthlyTrend: Array<{
    month: string;
    recognizedAmount: number;
    reviewedOrders: number;
  }>;
  recentOrders: Array<{
    orderId: string;
    orderNo: string;
    customerName: string;
    salesOwner: string;
    routeType: RouteType | null;
    priority: OrderPriority;
    status: OrderStatus;
    createdAt: string;
    reviewedAt: string | null;
    recognizedAmount: number;
    excludedLineCount: number;
  }>;
}

export interface WorkOrder {
  id: string;
  workOrderNo: string;
  orderId: string;
  orderLineId: string;
  productId: string;
  productCode: string;
  productName: string;
	productMaterial: string | null;
  routeType: RouteType;
  routeVersion: string;
	orderQuantity: number;
  plannedQuantity: number;
  status: string;
  batchId: string;
	batchNo: string;
	batchType: "PLANNED" | "FLOW_SPLIT";
	batchStatus: "PENDING_LAUNCH" | "READY" | "IN_PROGRESS" | "COMPLETED";
  taskCount: number;
  completedTaskCount: number;
	currentOperationName: string | null;
	currentTaskStatus: TaskStatus | null;
  createdAt: string;
}

export interface ReportLedgerItem {
  id: string;
  taskId: string;
  taskNo: string;
  orderId: string;
  orderNo: string;
  productCode: string;
  productName: string;
  routeType: RouteType;
  operationCode: string;
  operationName: string;
  operatorCode: string;
  recordedBy?: string;
  goodQuantity: number;
  scrapQuantity: number;
  deviceCode: string | null;
  workstationCode: string | null;
  photoUrl: string | null;
  occurredAt: string;
}

export interface Task {
  id: string;
  taskNo: string;
  workOrderId: string;
	workOrderNo: string;
	orderId: string;
	orderLineId: string;
	productCode: string;
	productName: string;
	productMaterial: string | null;
	routeType: RouteType;
  batchId: string;
  batchNo: string;
  sequenceNo: number;
  operationCode: string;
  operationName: string;
  shellLineMode?: "AUTOMATED" | "MANUAL" | null;
  plannedQuantity: number;
  goodQuantity: number;
  scrapQuantity: number;
  status: TaskStatus;
  assignedTo: string | null;
  reportingMode: TaskReportingMode;
  assignedQuantity: number | null;
  settlementUnit: SettlementUnit;
  countingDeferred: boolean;
  treeCount: number | null;
  piecesPerTree: number | null;
  compensationMode: CompensationMode;
  completedWeightKg: number | null;
  startedAt: string | null;
  completedAt: string | null;
  createdAt: string;
}

export interface PartialFlowRelease {
  sourceTaskId: string;
  targetBatchId: string;
  targetBatchNo: string;
  quantity: number;
  firstTask: Task;
  releasedAt: string;
}

export interface PostTreatmentDecision {
  id: string;
  batchId: string;
  sourceTaskId: string;
  destination: "IN_HOUSE" | "OUTSOURCE" | "DIRECT_FINISHED" | "FINISHED_GOODS_STORAGE";
  processSummary: string | null;
  processCodes: string[];
  supplierId: string | null;
  outsourcingOrderId: string | null;
  warehouseCode: string | null;
  note: string | null;
  decidedBy: string;
  decidedAt: string;
}

export interface DispatchRecommendation {
  tasks: Array<{
    taskId: string;
    taskNo: string;
    orderNo: string;
    workOrderNo: string;
    productCode: string;
    productName: string;
    operationCode: string;
    operationName: string;
    plannedQuantity: number;
		routeType: RouteType;
    priority: OrderPriority;
    requestedDeliveryDate: string | null;
  }>;
  workers: Array<{
    employeeCode: string;
    name: string;
    unitCode: string;
    roleCode: string;
    activeTaskCount: number;
    recommended: boolean;
  }>;
}

export interface ProductionReport {
  id: string;
  operationId: string;
  taskId: string;
  goodQuantity: number;
  scrapQuantity: number;
  operatorCode: string;
  recordedBy?: string;
  taskGoodTotal: number;
  taskScrapTotal: number;
  taskStatus: TaskStatus;
	deviceCode: string | null;
	workstationCode: string | null;
	photoUrl: string | null;
  occurredAt: string;
}

export interface UpstreamReports {
	sourceTaskId: string | null;
	sourceTaskNo: string | null;
	sourceOperationCode: string | null;
	sourceOperationName: string | null;
	reports: ProductionReport[];
}

export interface OrderTrace {
  order: Order;
  workOrders: Array<{
    workOrder: WorkOrder;
    tasks: Array<{ task: Task; reports: ProductionReport[] }>;
  }>;
  timeline: Array<{
    occurredAt: string;
    type: string;
    reference: string;
    summary: string;
  }>;
}

export interface ApiErrorBody {
  error: {
    code: string;
    message: string;
    details: Array<{ field: string; message: string }>;
  };
}

export interface QualityInspection {
  id: string;
  operationId: string;
  inspectionNo: string;
  taskId: string;
  inspectedQuantity: number;
  acceptedQuantity: number;
  rejectedQuantity: number;
  result: "PASSED" | "REJECTED";
  defectCode: string | null;
  inspectorCode: string;
  remark: string | null;
  dispositionCount: number;
  disposedQuantity: number;
  occurredAt: string;
}

export interface InventoryBalance {
  id: string;
  warehouseCode: string;
  itemCode: string;
  itemName: string;
  unit: string;
  quantity: number;
  version: number;
  updatedAt: string;
}

export interface InventoryMovement {
  id: string;
  operationId: string;
  movementNo: string;
  balanceId: string;
  warehouseCode: string;
  itemCode: string;
  itemName: string;
  unit: string;
  movementType: "RECEIPT" | "ISSUE" | "ADJUSTMENT_IN" | "ADJUSTMENT_OUT";
  quantity: number;
  balanceAfter: number;
  referenceType: string | null;
  referenceNo: string | null;
  operatorCode: string;
  remark: string | null;
  occurredAt: string;
}

export interface PageResult<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface InventoryExportJob {
  id: string;
  jobNo: string;
  status: "PENDING" | "RUNNING" | "COMPLETED" | "FAILED";
  fileName: string | null;
  requestedBy: string;
  createdAt: string;
  completedAt: string | null;
  errorMessage: string | null;
}

export interface PieceworkRate {
  id: string;
  operationCode: string;
  operationName: string;
  routeType: RouteType;
  productCode: string | null;
  productName: string | null;
  version: string;
  settlementUnit: SettlementUnit;
  unitRate: number;
  effectiveFrom: string;
  active: boolean;
  createdAt: string;
}

export interface PieceworkEntry {
  id: string;
  operationId: string;
  entryNo: string;
  taskId: string;
  taskNo: string;
  operationCode: string;
  operationName: string;
  workerCode: string;
  recordedBy: string;
  quantity: number;
  settlementUnit: SettlementUnit;
  rateVersion: string;
  unitRate: number;
  amount: number;
  status: "CANDIDATE" | "CONFIRMED" | "REVERSED";
  confirmedBy: string | null;
  settlementDate: string;
  occurredAt: string;
  confirmedAt: string | null;
}

export interface WorkerPayrollDetail {
  workerCode: string;
  workerName: string;
  orderNo: string;
  customerName: string;
  workOrderNo: string;
  batchNo: string;
  productCode: string;
  productName: string;
  productMaterial: string;
  routeType: RouteType;
  taskNo: string;
  operationName: string;
  settlementUnit: SettlementUnit;
  quantity: number;
  unitRate: number;
  amount: number;
  rateVersion: string;
  recordedBy: string;
  confirmedBy: string;
  settlementDate: string;
}

export interface WorkerOutputDetail {
  workerCode: string;
  workerName: string;
  orderNo: string;
  customerName: string;
  workOrderNo: string;
  batchNo: string;
  productCode: string;
  productName: string;
  productMaterial: string;
  routeType: RouteType;
  taskNo: string;
  operationName: string;
  goodQuantity: number;
  scrapQuantity: number;
  occurredAt: string;
  source: "ELECTRONIC_REPORT" | "PIECEWORK_SETTLEMENT";
}

export interface OutsourcingSupplier {
  id: string;
  code: string;
  name: string;
  contactName: string | null;
  contactPhone: string | null;
  active: boolean;
  createdAt: string;
}

export interface OutsourcingOrder {
  id: string;
  orderNo: string;
  supplierId: string;
  supplierCode: string;
  supplierName: string;
  itemCode: string;
  itemName: string;
  quantity: number;
  receivedQuantity: number;
  unit: string;
  dueDate: string | null;
  status: "DRAFT" | "SENT" | "IN_PROGRESS" | "RECEIVED" | "CLOSED" | "CANCELLED";
  remark: string | null;
  planningTaskId: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface ManagementDashboard {
  overview: {
    orders: number;
    draftOrders: number;
    workOrders: number;
    activeTasks: number;
    readyTasks: number;
    rejectedInspections: number;
    openOutsourcing: number;
    confirmedPieceworkAmount: number | null;
  };
  taskStatuses: Array<{ status: string; count: number }>;
  dailyOutput: Array<{ date: string; goodQuantity: number; scrapQuantity: number }>;
  quality: { acceptedQuantity: number; rejectedQuantity: number };
  warehouseSkus: Array<{ name: string; value: number }>;
  outsourcingStatuses: Array<{ name: string; value: number }>;
  workerAmounts: Array<{ workerCode: string; amount: number }>;
  risks: Array<{ severity: string; title: string; count: number; link: string }>;
  generatedAt: string;
}

export interface WorkflowRequest {
  id: string;
  requestNo: string;
  workflowType: string;
  businessKey: string;
  title: string;
  requesterCode: string;
  status: "PENDING" | "APPROVED" | "REJECTED";
  requiredApprovals: number;
  approvalCount: number;
  payload: string | null;
  createdAt: string;
  completedAt: string | null;
}

export interface WorkflowDefinition {
  id: string;
  workflowType: string;
  name: string;
  description: string | null;
  requiredApprovals: number;
  approverRoles: string[];
  active: boolean;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

export interface ConfigurationPackage {
  id: string;
  packageNo: string;
  configType: string;
  name: string;
  version: string;
  content: string;
  status: "DRAFT" | "PUBLISHED" | "ROLLED_BACK";
  createdBy: string;
  createdAt: string;
  publishedBy: string | null;
  publishedAt: string | null;
  rolledBackBy: string | null;
  rolledBackAt: string | null;
}

export interface OrganizationUnit {
  id: string;
  code: string;
  name: string;
  unitType: "COMPANY" | "FACTORY" | "WORKSHOP" | "TEAM";
  parentCode: string | null;
  active: boolean;
  createdAt: string;
}

export interface OrganizationMember {
  id: string;
  employeeCode: string;
  name: string;
  unitCode: string;
  roleCode: string;
  active: boolean;
  createdAt: string;
}

export interface ResourceAsset {
  id: string;
  assetCode: string;
  assetName: string;
    assetType: "MOLD" | "EQUIPMENT" | "TREE" | "FURNACE" | "CARRIER";
    status: "AVAILABLE" | "OCCUPIED" | "EXHAUSTED";
    ownershipType: "COMPANY_OWNED" | "CUSTOMER_OWNED";
    ownerName: string | null;
		moldCustodyStatus: "IN_STOCK" | "INTERNAL_IN_USE" | "EXTERNAL_OUT";
  locationCode: string | null;
  moldImageUrl: string | null;
  lifeLimit: number | null;
  lifeUsed: number;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface MoldLifecycle {
  id: string;
  assetCode: string;
  assetName: string;
  status: string;
  locationCode: string | null;
  lifeLimit: number | null;
  lifeUsed: number;
  maintenanceIntervalDays: number | null;
  nextMaintenanceDate: string | null;
  lockReason: string | null;
  lockedAt: string | null;
}

export interface MoldLocationSuggestion {
  locationCode: string;
  occupiedCount: number;
  capacity: number;
}

export interface MoldMaintenanceRecord {
  id: string;
  moldAssetId: string;
  recordNo: string;
  recordType: "REPAIR" | "MAINTENANCE" | "INSPECTION" | "UNLOCK";
  description: string;
  serviceProvider: string | null;
  performedBy: string;
  occurredAt: string;
  nextMaintenanceDate: string | null;
}

export interface PaperReportSheet {
  id: string;
  sheetNo: string;
  paperFormNo: string | null;
  paperImageUrl: string | null;
  taskId: string;
  workerCode: string;
  reportKind: "QUANTITY" | "HOURS";
  goodQuantity: number | null;
  scrapQuantity: number | null;
  hours: number | null;
  note: string | null;
  enteredBy: string;
  enteredAt: string;
  status: "PENDING" | "APPROVED" | "REJECTED";
  reviewedBy: string | null;
  reviewedAt: string | null;
  rejectedBy: string | null;
  rejectedAt: string | null;
  rejectionReason: string | null;
}

export interface CartSource {
  id: string;
  taskNo: string;
  operationName: string;
  routeType: RouteType;
  productName: string;
  productMaterial: string | null;
  orderNo: string;
  batchNo: string;
  availableQuantity: number;
}

export interface CartTransfer {
  id: string;
  transferNo: string;
  cartCode: string;
  cartName: string;
  sourceTaskId: string;
  sourceTaskNo: string;
  sourceOperationName: string;
  targetTaskId: string;
  targetTaskNo: string;
  targetOperationName: string;
  orderNo: string;
  productCode: string;
  productName: string;
  loadedQuantity: number;
  receivedQuantity: number | null;
  status: "LOADED" | "RECEIVED" | "EXCEPTION";
  loadPhotoUrl: string | null;
  receivePhotoUrl: string | null;
  loadedBy: string;
  receivedBy: string | null;
  exceptionReason: string | null;
  loadedAt: string;
  receivedAt: string | null;
}

export interface ResourceOccupation {
  id: string;
  assetId: string;
  businessKey: string;
  status: "ACTIVE" | "RELEASED";
  occupiedBy: string;
  occupiedAt: string;
  releasedBy: string | null;
  releasedAt: string | null;
  note: string | null;
}

export interface AssetQrLabel {
  id: string;
  labelNo: string;
  qrToken: string;
  intendedAssetType: "MOLD" | "CARRIER";
  assetId: string | null;
  assetCode: string | null;
  assetName: string | null;
  status: "UNBOUND" | "BOUND";
  printCount: number;
  lastPrintedBy: string | null;
  lastPrintedAt: string | null;
  boundBy: string | null;
  boundAt: string | null;
  createdBy: string;
  createdAt: string;
}

export interface MoldRequest {
  id: string;
  requestNo: string;
  moldAssetId: string | null;
    productCode: string;
    status: "CUSTOM_MOLD_REQUIRED" | "REQUESTED" | "APPROVED" | "ISSUED" | "RETURNED";
    moldOwnershipType: "COMPANY_OWNED" | "CUSTOMER_OWNED" | null;
    moldOwnerName: string | null;
  requestedBy: string;
  engineerCode: string | null;
  warehouseCode: string | null;
  requestedAt: string;
  approvedAt: string | null;
  issuedAt: string | null;
  returnedAt: string | null;
	orderId: string | null;
	orderLineId: string | null;
	workOrderId: string | null;
	waxTaskId: string | null;
	issuedToWorkerCode: string | null;
}

export interface OrderMoldSelection {
  id: string;
  orderId: string;
  orderNo: string;
  customerId: string;
  customerName: string;
  orderLineId: string;
  lineNo: number;
  productCode: string;
  productName: string;
  routeType: RouteType;
  moldAssetId: string | null;
  moldAssetCode: string | null;
  moldAssetName: string | null;
  moldOwnershipType: "COMPANY_OWNED" | "CUSTOMER_OWNED" | null;
  moldOwnerName: string | null;
  selectionStatus: "SELECTED" | "CUSTOM_MOLD_TO_RECEIVE" | "CUSTOMER_DELIVERY_PENDING";
  pendingReason: string | null;
  selectedBy: string;
  selectedAt: string;
  updatedAt: string;
}

export interface MoldExternalMovement {
  id: string;
  movementNo: string;
  moldAssetId: string;
  moldAssetCode: string;
  moldAssetName: string;
  locationCode: string | null;
  reasonCode: "CUSTOMER_RECALL" | "MAINTENANCE" | "OTHER";
  reasonNote: string | null;
  counterpartyName: string | null;
  expectedReturnDate: string | null;
  status: "OPEN" | "RETURNED";
  checkedOutBy: string;
  checkedOutAt: string;
  returnedBy: string | null;
  returnedAt: string | null;
}

export interface ScheduleQueueItem {
  id: string;
  taskId: string;
  lineCode: "MID_WAX" | "LOW_WAX" | "SAND_OUTSOURCE";
  taskNo: string;
  workOrderId: string;
  orderId: string;
  orderNo: string;
  priority: OrderPriority;
  requestedDeliveryDate: string | null;
  operationCode: string;
  operationName: string;
  taskStatus: TaskStatus;
  plannedQuantity: number;
  assignedTo: string | null;
  startedAt: string | null;
  manualRank: number;
  createdAt: string;
}

export interface NotificationItem {
  id: string;
  notificationNo: string;
  recipientCode: string;
  category: string;
  title: string;
  content: string;
  businessLink: string | null;
  status: "UNREAD" | "READ";
  createdAt: string;
  readAt: string | null;
}

export interface ProductionShortageAlert {
  id: string;
  workOrderId: string;
  orderId: string;
  orderLineId: string;
	orderNo: string;
	workOrderNo: string;
	productName: string;
  routeType: RouteType;
  demandQuantity: number;
  projectedQuantity: number;
  shortageQuantity: number;
  status: "OPEN" | "RESOLVED";
  detectedAt: string;
  lastEvaluatedAt: string;
}

export interface ManualShellDryingAlert {
  id: string;
  taskId: string;
  taskNo: string;
  operationName: string;
  orderId: string;
  orderNo: string;
  productName: string;
  productMaterial: string | null;
  routeType: RouteType;
  layerCount: number;
  waitingSince: string;
  waitingHours: number;
  firstDetectedAt: string;
  lastEvaluatedAt: string;
}

export interface ProcessCardTemplate {
  id: string;
  templateNo: string;
  productId: string;
  productCode: string;
  productName: string;
  routeType: RouteType;
  version: string;
  status: "DRAFT" | "PUBLISHED" | "RETIRED";
  engineeringParameters: string;
  operationParameters: string;
  createdBy: string;
  publishedBy: string | null;
  createdAt: string;
  publishedAt: string | null;
}

export interface ProcessCardAiSuggestion {
  engineeringParameters: string;
  operationParameters: Record<string, string>;
  note: string;
}

export interface AiAdvice {
  title: string;
  summary: string;
  actions: string[];
  caution: string;
}

export interface AiSopDraft {
  safetyNotice: string;
  preparationNote: string;
  steps: Array<{ title: string; instruction: string }>;
  qualityPoints: string[];
  caution: string;
}

export interface HandoffException {
  sourceType: "HANDOFF" | "CART";
  id: string;
  referenceNo: string;
  taskId: string;
  taskNo: string;
  operationName: string;
  orderNo: string;
  productName: string;
  expectedQuantity: number;
  receivedQuantity: number | null;
  reason: string;
  evidenceUrl: string | null;
  handedOverBy: string;
  receivedBy: string | null;
  occurredAt: string;
  resolutionStatus: "OPEN" | "IN_PROGRESS" | "RESOLVED";
  ownerCode: string | null;
  resolutionNote: string | null;
  resolvedBy: string | null;
  resolvedAt: string | null;
}

export type ScanResolution =
  | { kind: "task"; task: Task; asset: null; batchId: null; batchNo: null; tasks: Task[] }
  | { kind: "asset"; task: null; asset: ResourceAsset; batchId: null; batchNo: null; tasks: Task[] }
  | { kind: "batch"; task: null; asset: null; batchId: string; batchNo: string; tasks: Task[] };

export interface HandoffReceipt {
  receivingTaskId: string;
  sourceTaskId: string;
  sourceTaskNo: string;
  sourceOperationName: string;
  expectedQuantity: number;
  receivedQuantity: number | null;
  status: "PENDING_RECEIPT" | "MATCHED" | "EXCEPTION" | "NOT_REQUIRED";
  exceptionType: string | null;
  exceptionReason: string | null;
  photoUrl: string | null;
  receivedBy: string | null;
  receivedAt: string | null;
}

export interface ShellRecord {
  id: string;
  taskId: string;
  method: "MANUAL" | "AUTOMATED" | "TRANSFERRED";
  layerCount: number;
  dryingMinutes: number;
  quantity: number;
  operatorCode: string;
  note: string | null;
  nextAction: "WAIT_NEXT_LAYER" | "FLOW_TO_NEXT";
  photoUrl: string | null;
  occurredAt: string;
}

export interface ShellProgressSummary {
  taskId: string;
  recordCount: number;
  latestLayer: number;
  flowedToNext: boolean;
  latestOccurredAt: string;
}

export interface FurnaceBatch {
  id: string;
  furnaceBatchNo: string;
  operationCode: "DEWAX" | "POURING";
  furnaceAssetId: string | null;
  furnaceCode: string | null;
  furnaceName: string | null;
  materialBatch: string | null;
  chargeQuantity: number;
  targetTemperature: number | null;
  actualTemperature: number | null;
  pressureMpa: number | null;
  status: "OPEN" | "COMPLETED";
  note: string | null;
  createdBy: string;
  createdAt: string;
  completedBy: string | null;
  completedAt: string | null;
  taskReferences: string;
}

export interface IntegrationJob {
  id: string;
  operationId: string;
  jobNo: string;
  interfaceCode: string;
  businessKey: string;
  direction: "INBOUND" | "OUTBOUND";
  payload: string;
  status: "PENDING" | "PROCESSING" | "FAILED" | "RETRYING" | "SUCCEEDED";
  attemptCount: number;
  lastError: string | null;
  nextRetryAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface OperationsOverview {
  failedIntegrationJobs: number;
  unreadNotifications: number;
  occupiedResources: number;
  exhaustedResources: number;
  pendingWorkflows: number;
  publishedConfigurations: number;
  generatedAt: string;
}

export interface AccessUser {
  employeeCode: string;
  name: string;
  unitCode: string;
  unitName: string;
  primaryRole: string;
  roles: string[];
  permissions: string[];
  active: boolean;
  loginInitialized?: boolean;
  mustChangePassword?: boolean;
  lastLoginAt?: string | null;
}

export interface UserDataScope {
  employeeCode: string;
  supervisorRoutes: RouteType[];
  operatorRoutes: RouteType[];
  supervisorOperations: Array<{ routeType: RouteType; operationCode: string }>;
}

export interface ReportFormProfile {
  operationCode: string;
  showPhoto: boolean;
  requirePhoto: boolean;
  showDevice: boolean;
  requireDevice: boolean;
  showWorkstation: boolean;
  requireWorkstation: boolean;
  updatedBy: string;
  updatedAt: string | null;
}

export interface AccessRole {
  code: string;
  name: string;
  description: string;
  permissions: string[];
}

export interface AccessPermission {
  code: string;
  name: string;
  moduleCode: string;
}

export type DocumentType = "PROCESS_CARD" | "WORKSHOP_JOB_SHEET" | "FLOW_CARD" | "ORDER_DETAIL" | "STATISTICS";
export type DocumentOutput = "PREVIEW" | "PRINT" | "EXPORT";

export interface DocumentOption {
  documentType: DocumentType;
  title: string;
  containsSensitiveData: boolean;
}

export interface PrintableField {
  code: string;
  label: string;
  value: string;
  sensitive: boolean;
}

export interface DocumentPreview {
  documentType: DocumentType;
  title: string;
  entityId: string | null;
  generatedAt: string;
  sensitiveIncluded: boolean;
  fields: PrintableField[];
}

export interface DocumentAudit {
  id: string;
  documentType: DocumentType;
  entityId: string | null;
  actorCode: string;
  selectedFields: string[];
  sensitiveIncluded: boolean;
  outputType: DocumentOutput;
  occurredAt: string;
}

export interface SopStep {
  stepNo: number;
  title: string;
  instruction: string;
}

export interface SopKeyParameter {
  name: string;
  value: string;
}

export interface OperationSop {
  id: string;
  operationCode: string;
  operationName: string;
  version: string;
  safetyNotice: string;
  preparationNote: string;
  status: string;
  updatedBy: string;
  updatedAt: string;
  steps: SopStep[];
  qualityPoints: string[];
  keyParameters: SopKeyParameter[];
}

export interface FinishedGoodsLot {
  id: string;
  operationId: string;
  lotNo: string;
  orderId: string;
  orderNo: string;
  taskId: string;
  productCode: string;
  productName: string;
  quantity: number;
  availableQuantity: number;
  warehouseCode: string;
  registeredBy: string;
  registeredAt: string;
}

export interface PendingFinishedGoodsReceipt {
  taskId: string;
  taskNo: string;
  batchNo: string;
  orderId: string;
  orderNo: string;
  productCode: string;
  productName: string;
  productMaterial: string | null;
  finalCountQuantity: number;
  allowedQuantity: number;
  registeredQuantity: number;
  receivableQuantity: number;
  qualityInspected: boolean;
}

export type DeliveryStatus = "DRAFT" | "PICKED" | "SHIPPED" | "DELIVERED";

export interface DeliveryOrder {
  id: string;
  deliveryNo: string;
  orderId: string;
  orderNo: string;
  customerName: string;
  lotId: string;
  lotNo: string;
  productCode: string;
  productName: string;
  quantity: number;
  recipientName: string;
  deliveryAddress: string;
  carrier: string | null;
  trackingNo: string | null;
  status: DeliveryStatus;
  createdBy: string;
  createdAt: string;
  pickedAt: string | null;
  shippedAt: string | null;
  deliveredAt: string | null;
  updatedAt: string;
}
