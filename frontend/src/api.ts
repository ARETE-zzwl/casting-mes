import type {
  AccessPermission,
  AssetQrLabel,
  AccessRole,
  AccessUser,
	UserDataScope,
	ReportFormProfile,
	CompensationMode,
  ApiErrorBody,
  DocumentAudit,
  DocumentOption,
  DocumentOutput,
  DocumentPreview,
  DocumentType,
  ConfigurationPackage,
  Customer,
	CustomerSensitiveRequest,
  CartTransfer,
  CartSource,
  InventoryBalance,
  InventoryMovement,
  InventoryExportJob,
  PageResult,
  ManagementDashboard,
  MoldRequest,
	MoldExternalMovement,
	MoldLifecycle,
	MoldMaintenanceRecord,
	MoldLocationSuggestion,
	PaperReportSheet,
	ShellRecord,
  OrderMoldSelection,
	NotificationItem,
	ProductionShortageAlert,
  OperationsOverview,
  Order,
  OrderPriority,
  OrderTrace,
  Product,
  ProductionReport,
  PieceworkEntry,
  PieceworkRate,
  WorkerOutputDetail,
  WorkerPayrollDetail,
  OutsourcingOrder,
  OutsourcingSupplier,
  OrganizationMember,
  OrganizationUnit,
  QualityInspection,
  ResourceAsset,
  ResourceOccupation,
  ScheduleQueueItem,
  RouteType,
	SalesPerformance,
  SettlementUnit,
  Task,
  TaskReportingMode,
  TaskStatus,
  WorkflowRequest,
	WorkflowDefinition,
  IntegrationJob,
  FinishedGoodsLot,
	PendingFinishedGoodsReceipt,
  DeliveryOrder,
	ProcessCardTemplate,
	HandoffException,
	HandoffReceipt,
	FurnaceBatch,
	ProcessCardAiSuggestion,
	PostTreatmentDecision,
	AiAdvice,
	AiSopDraft,
  DispatchRecommendation,
  OperationSop,
  PartialFlowRelease,
  WorkOrder
} from "./types";

export class ApiError extends Error {
  constructor(
    message: string,
    public readonly code: string,
    public readonly status: number,
    public readonly details: Array<{ field: string; message: string }> = []
  ) {
    super(message);
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const protection = init?.method && !["GET", "HEAD"].includes(init.method) ? await csrfHeaders() : {};
  const response = await fetch(path, {
    ...init,
		credentials: "include",
    headers: {
      Accept: "application/json",
      ...protection,
      ...(init?.body ? { "Content-Type": "application/json" } : {}),
      ...init?.headers
    }
  });

  if (!response.ok) {
    if (response.status === 403) csrfRequest = undefined;
    if (response.status === 401 && !path.startsWith("/api/auth/")) window.dispatchEvent(new Event("mes:session-expired"));
    const payload = (await response.json().catch(() => null)) as ApiErrorBody | null;
    throw new ApiError(
      payload?.error?.message ?? (response.status === 401 ? "登录已失效，请重新登录" : "服务暂时不可用"),
      payload?.error?.code ?? "REQUEST_FAILED",
      response.status,
      payload?.error?.details ?? []
    );
  }

  const text = await response.text();
  if (path === "/api/auth/login" || path === "/api/auth/logout") csrfRequest = undefined;
  return (text ? JSON.parse(text) : undefined) as T;
}

let csrfRequest: Promise<Record<string, string>> | undefined;
async function csrfHeaders(): Promise<Record<string, string>> {
  csrfRequest ??= fetch("/api/auth/csrf", { credentials: "include" }).then(async (response) => {
    if (!response.ok) throw new Error("无法验证请求，请刷新后重试");
    const value = await response.json() as { token?: string; headerName?: string };
    return value.token && value.headerName ? { [value.headerName]: value.token } : {};
  }).catch((error) => { csrfRequest = undefined; throw error; });
  return csrfRequest;
}

async function download(path: string): Promise<Blob> {
  const response = await fetch(path, { credentials: "include", headers: { Accept: "text/csv, text/plain, application/octet-stream" } });
  if (!response.ok) {
    if (response.status === 401) window.dispatchEvent(new Event("mes:session-expired"));
    const payload = (await response.json().catch(() => null)) as ApiErrorBody | null;
    throw new ApiError(
      payload?.error?.message ?? "导出失败",
      payload?.error?.code ?? "EXPORT_FAILED",
      response.status,
      payload?.error?.details ?? []
    );
  }
  return response.blob();
}

async function uploadFile(category: "ORDER_DRAWING" | "ORDER_CONTRACT" | "PRODUCT_MODEL" | "MOLD_IMAGE" | "PROCESS_CARD_IMAGE" | "EXECUTION_PHOTO", file: File): Promise<{ url: string; originalName: string; size: number; contentType: string }> {
  const form = new FormData();
  form.append("category", category);
  form.append("file", file);
  const response = await fetch("/api/files/upload", { method: "POST", credentials: "include", headers: { Accept: "application/json", ...await csrfHeaders() }, body: form });
  if (!response.ok) {
    if (response.status === 401) window.dispatchEvent(new Event("mes:session-expired"));
    if (response.status === 403) csrfRequest = undefined;
    const payload = (await response.json().catch(() => null)) as ApiErrorBody | null;
    throw new ApiError(payload?.error?.message ?? "文件上传失败", payload?.error?.code ?? "FILE_UPLOAD_FAILED", response.status, payload?.error?.details ?? []);
  }
  return response.json();
}

const post = <T>(path: string, body?: unknown, headers?: HeadersInit) =>
  request<T>(path, {
    method: "POST",
    body: body === undefined ? undefined : JSON.stringify(body),
    headers
  });

export const api = {
	auth: {
        config: () => request<{ authenticationRequired: boolean }>("/api/auth/config"),
		login: (input: { employeeCode: string; password: string }) => post<AuthSession>("/api/auth/login", input),
		me: () => request<AuthSession>("/api/auth/me"),
		logout: () => post<void>("/api/auth/logout"),
		changePassword: (input: { currentPassword: string; newPassword: string }) => post<AuthSession>("/api/auth/password", input)
	},
	files: { upload: uploadFile },
  customers: {
    list: () => request<Customer[]>("/api/customers"),
    create: (input: {
      code?: string;
      name: string;
      contactName?: string;
      contactPhone?: string;
      salesOwner?: string;
    }) => post<Customer>("/api/customers", input),
    sensitiveRequests: () => request<CustomerSensitiveRequest[]>("/api/customers/sensitive-requests"),
    requestCreate: (input: { requesterCode: string; code?: string; name: string; contactName?: string; contactPhone?: string; salesOwner?: string; requestNote?: string }) =>
      post<CustomerSensitiveRequest>("/api/customers/sensitive-requests", input),
    approveSensitiveRequest: (id: string, reviewerCode: string, reviewNote?: string) =>
      post<CustomerSensitiveRequest>(`/api/customers/sensitive-requests/${id}/approve`, { reviewerCode, reviewNote }),
    rejectSensitiveRequest: (id: string, reviewerCode: string, reviewNote: string) =>
      post<CustomerSensitiveRequest>(`/api/customers/sensitive-requests/${id}/reject`, { reviewerCode, reviewNote })
  },
  products: {
    list: () => request<Product[]>("/api/products"),
    create: (input: {
      code?: string;
      name: string;
      routeType: RouteType;
      routeVersion: string;
      modelImageUrl?: string;
		specification?: string;
		material?: string;
    }) => post<Product>("/api/products", input),
    update: (id: string, input: {
      name: string;
      routeType: RouteType;
      routeVersion: string;
      specification?: string;
      material?: string;
    }) => post<Product>(`/api/products/${id}`, input),
    updateModelImage: (id: string, modelImageUrl?: string) => post<Product>(`/api/products/${id}/model-image`, { modelImageUrl })
  },
	productMolds: {
		list: (productId?: string) => request<import("./types").ProductMoldRelation[]>(`/api/product-molds${productId ? `?productId=${encodeURIComponent(productId)}` : ""}`),
		bind: (input: { productId: string; moldAssetId: string; note?: string; operatorCode: string }) => post<import("./types").ProductMoldRelation>("/api/product-molds", input),
		unbind: (id: string) => post<void>(`/api/product-molds/${id}/unbind`)
	},
	customerProductMolds: {
		list: (customerId?: string, productId?: string) => {
			const params = new URLSearchParams();
			if (customerId) params.set("customerId", customerId);
			if (productId) params.set("productId", productId);
			return request<import("./types").CustomerProductMoldRelation[]>(`/api/customer-product-molds${params.size ? `?${params}` : ""}`);
		},
		history: (customerId?: string, productId?: string) => {
			const params = new URLSearchParams();
			if (customerId) params.set("customerId", customerId);
			if (productId) params.set("productId", productId);
			return request<import("./types").CustomerProductOrderHistory[]>(`/api/customer-product-molds/history${params.size ? `?${params}` : ""}`);
		},
		bind: (input: { customerId: string; productId: string; moldAssetId: string; operatorCode: string }) =>
			post<import("./types").CustomerProductMoldRelation>("/api/customer-product-molds", input),
		unbind: (id: string) => post<void>(`/api/customer-product-molds/${id}/unbind`)
	},
	processCardTemplates: {
		list: (productId?: string, publishedOnly = false) => {
			const params = new URLSearchParams();
			if (productId) params.set("productId", productId);
			if (publishedOnly) params.set("publishedOnly", "true");
			return request<ProcessCardTemplate[]>(`/api/process-card-templates${params.size ? `?${params}` : ""}`);
		},
		create: (input: { productId: string; version: string; engineeringParameters: string; operationParameters: string; createdBy: string }) =>
			post<ProcessCardTemplate>("/api/process-card-templates", input),
		publish: (id: string, publishedBy: string) => post<ProcessCardTemplate>(`/api/process-card-templates/${id}/publish`, { publishedBy }),
		suggest: (input: { productId: string; sourceTemplateId?: string; currentEngineeringParameters?: string; currentOperationParameters: string; operatorCode: string }) =>
			post<ProcessCardAiSuggestion>("/api/process-card-templates/ai-suggestion", input)
	},
	aiAssistant: {
		handoff: (sourceType: "HANDOFF" | "CART", id: string, operatorCode: string) =>
			post<AiAdvice>(`/api/ai-assistant/handoff/${sourceType}/${id}`, { operatorCode }),
		scheduleRisk: (lineCode: string, operatorCode: string) =>
			post<AiAdvice>("/api/ai-assistant/schedule-risk", { lineCode, operatorCode }),
		furnace: (id: string, operatorCode: string) =>
			post<AiAdvice>(`/api/ai-assistant/furnace/${id}`, { operatorCode }),
		sopDraft: (input: { operationCode: string; operationName: string; engineeringRequirements?: string; routeType?: string; operatorCode: string }) =>
			post<AiSopDraft>("/api/ai-assistant/sop-draft", input)
	},
  orders: {
    list: (routeType?: RouteType) =>
      request<Order[]>(`/api/orders${routeType ? `?routeType=${routeType}` : ""}`),
    create: (input: {
      orderNo?: string;
      customerId: string;
      priority: OrderPriority;
      requestedDeliveryDate?: string;
      remark?: string;
		createdBy?: string;
		orderDrawingUrl?: string;
		contractAttachmentUrl?: string;
      lines: Array<{ productId: string; quantity: number; unit: string; salesUnitPrice: number; salesPriceUnit: "TON" | "KG" | "PCS" | "SET" | "EA"; productMaterial?: string }>;
    }) => post<Order>("/api/orders", input),
    updateDraft: (id: string, input: {
      editorCode: string; customerId: string; priority: OrderPriority; requestedDeliveryDate?: string; remark?: string;
      orderDrawingUrl?: string; contractAttachmentUrl?: string;
      lines: Array<{ productId: string; quantity: number; unit: string; salesUnitPrice: number; salesPriceUnit: "TON" | "KG" | "PCS" | "SET" | "EA"; productMaterial?: string }>;
    }) => post<Order>(`/api/orders/${id}/draft`, input),
    submit: (id: string, editorCode: string) => post<Order>(`/api/orders/${id}/submit`, { editorCode }),
    approve: (id: string) => post<Order>(`/api/orders/${id}/approval`),
		reviewByCustomerManager: (id: string, managerCode: string, note?: string) => post<Order>(`/api/orders/${id}/customer-manager-review`, { managerCode, note }),
		returnByCustomerManager: (id: string, managerCode: string, reason: string) =>
			post<Order>(`/api/orders/${id}/customer-manager-return`, { managerCode, reason }),
		reviewByGeneralManager: (id: string, managerCode: string, note?: string) => post<Order>(`/api/orders/${id}/general-manager-review`, { managerCode, note }),
		returnByGeneralManager: (id: string, managerCode: string, reason: string) =>
			post<Order>(`/api/orders/${id}/general-manager-return`, { managerCode, reason }),
		confirmEngineering: (id: string, input: { engineerCode: string; lines: Array<{ orderLineId: string; processCardVersion?: string; engineeringParameters: string; engineeringOperationParameters?: string }> }) =>
			post<Order>(`/api/orders/${id}/engineering-confirmation`, input),
		returnForEngineering: (id: string, engineerCode: string, reason: string) =>
			post<Order>(`/api/orders/${id}/engineering-return`, { engineerCode, reason }),
		remindEngineering: (id: string, supervisorCode: string) =>
			post<Order>(`/api/orders/${id}/engineering-reminders`, { supervisorCode }),
		defaultProcessRelease: (id: string, input: { supervisorCode: string; processCardVersion?: string; engineeringParameters: string; engineeringOperationParameters: string }) =>
			post<Order>(`/api/orders/${id}/default-process-release`, input),
    release: (id: string) => post<WorkOrder[]>(`/api/orders/${id}/release`)
  },
	workOrders: {
    list: (routeType?: RouteType) =>
      request<WorkOrder[]>(`/api/work-orders${routeType ? `?routeType=${routeType}` : ""}`),
		configureBatches: (id: string, quantities: number[], supervisorCode: string) =>
			post<WorkOrder[]>(`/api/work-orders/${id}/batches`, { quantities, supervisorCode })
		,
		applyInitialProductionQuantity: (id: string, productionQuantity: number, supervisorCode: string) =>
			post<WorkOrder[]>(`/api/work-orders/${id}/production-quantity`, { productionQuantity, supervisorCode })
		,
		launchBatch: (batchId: string, productionQuantity: number, supervisorCode: string) =>
			post<WorkOrder>(`/api/batches/${batchId}/launch`, { productionQuantity, supervisorCode })
	},
  tasks: {
    list: (status?: TaskStatus, assignedTo?: string, orderId?: string, supervisorCode?: string) => {
      const params = new URLSearchParams();
      if (status) params.set("status", status);
      if (assignedTo) params.set("assignedTo", assignedTo);
      if (orderId) params.set("orderId", orderId);
			if (supervisorCode) params.set("supervisorCode", supervisorCode);
      const query = params.size ? `?${params}` : "";
      return request<Task[]>(`/api/tasks${query}`);
    },
	assign: (
      id: string,
      input: {
        workerCode: string;
        reportingMode?: TaskReportingMode;
        fixedQuantity?: number;
		settlementUnit?: SettlementUnit;
		compensationMode?: CompensationMode;
		supervisorCode?: string;
      }
    ) => post<Task>(`/api/tasks/${id}/assignment`, input),
		configureShellLine: (id: string, shellLineMode: "AUTOMATED" | "MANUAL", supervisorCode: string) =>
			post<Task>(`/api/tasks/${id}/shell-line`, { shellLineMode, supervisorCode }),
    dispatchWaxWithMold: (
      id: string,
      input: {
        moldAssetId: string;
        warehouseCode: string;
        warehouseOperatorCode: string;
        workerCode: string;
        reportingMode: TaskReportingMode;
        fixedQuantity?: number;
		settlementUnit?: SettlementUnit;
		compensationMode?: CompensationMode;
        productionQuantity?: number;
        supervisorCode: string;
      }
    ) => post<Task>(`/api/tasks/${id}/wax-dispatch`, input),
		returnWaxMold: (id: string, supervisorCode: string) =>
			post<Task>(`/api/tasks/${id}/mold-return`, { supervisorCode }),
    start: (id: string, operatorCode: string) =>
      post<Task>(`/api/tasks/${id}/start`, undefined, { "X-Operator-Code": operatorCode }),
    claimable: (workerCode: string) => request<Task[]>(`/api/tasks/claimable?workerCode=${encodeURIComponent(workerCode)}`),
    claimAndStart: (id: string, operatorCode: string) =>
      post<Task>(`/api/tasks/${id}/claim-and-start`, undefined, { "X-Operator-Code": operatorCode }),
		claim: (id: string, operatorCode: string) =>
			post<Task>(`/api/tasks/${id}/claim`, undefined, { "X-Operator-Code": operatorCode }),
		releasePartialFlow: (id: string, quantity: number, releasedBy: string, handoffPhotoUrl?: string) =>
			post<PartialFlowRelease>(`/api/tasks/${id}/partial-flow`, { quantity, releasedBy, handoffPhotoUrl }),
    report: (
      id: string,
      operatorCode: string,
      input: { operationId: string; goodQuantity: number; scrapQuantity: number; deviceCode?: string; workstationCode?: string; photoUrl?: string }
    ) =>
      post<{ report: ProductionReport; duplicate: boolean }>(
        `/api/tasks/${id}/reports`,
        input,
        { "X-Operator-Code": operatorCode }
      ),
		upstreamReports: (id: string) => request<import("./types").UpstreamReports>(`/api/tasks/${id}/reports/upstream`),
		supervisorReport: (
		  id: string,
		  input: { operationId: string; goodQuantity: number; scrapQuantity: number; supervisorCode: string; deviceCode?: string; workstationCode?: string; photoUrl?: string }
		) => post<{ report: ProductionReport; duplicate: boolean }>(`/api/tasks/${id}/supervisor-reports`, input),
    handoffWithoutCount: (id: string, operatorCode: string) =>
      post<Task>(`/api/tasks/${id}/handoff-without-count`, undefined, {
        "X-Operator-Code": operatorCode
      }),
		handoffReceipt: (id: string) => request<HandoffReceipt>(`/api/tasks/${id}/handoff-receipt`),
		acceptHandoffReceipt: (id: string, operatorCode: string, input: { receivedQuantity: number; exceptionType?: string; exceptionReason?: string; photoUrl?: string; deviceCode?: string; workstationCode?: string }) =>
			post<HandoffReceipt>(`/api/tasks/${id}/handoff-receipt`, input, { "X-Operator-Code": operatorCode }),
    reportTree: (
      id: string,
      operatorCode: string,
      input: { operationId: string; treeCount: number; piecesPerTree: number; scrapQuantity: number; deviceCode?: string; workstationCode?: string; photoUrl?: string }
    ) => post<{
      report: ProductionReport;
      treeCount: number;
      piecesPerTree: number;
      duplicate: boolean;
    }>(`/api/tasks/${id}/tree-reports`, input, { "X-Operator-Code": operatorCode })
	},
  execution: {
		reportLedger: (input: { viewerCode: string; workerCode?: string; orderId?: string; operationCode?: string; from?: string; to?: string }) => {
			const params = new URLSearchParams({ viewerCode: input.viewerCode });
			if (input.workerCode) params.set("workerCode", input.workerCode);
			if (input.orderId) params.set("orderId", input.orderId);
			if (input.operationCode) params.set("operationCode", input.operationCode);
			if (input.from) params.set("from", input.from);
			if (input.to) params.set("to", input.to);
			return request<import("./types").ReportLedgerItem[]>(`/api/execution/reports?${params}`);
		},
		reportLedgerPage: (input: { viewerCode: string; workerCode?: string; orderId?: string; operationCode?: string; from?: string; to?: string; page?: number; size?: number }) => {
			const params = new URLSearchParams({ viewerCode: input.viewerCode, page: String(input.page ?? 0), size: String(input.size ?? 20) });
			if (input.workerCode) params.set("workerCode", input.workerCode);
			if (input.orderId) params.set("orderId", input.orderId);
			if (input.operationCode) params.set("operationCode", input.operationCode);
			if (input.from) params.set("from", input.from);
			if (input.to) params.set("to", input.to);
			return request<PageResult<import("./types").ReportLedgerItem>>(`/api/execution/reports/query?${params}`);
		}
	},
	reportFormProfiles: {
		list: () => request<ReportFormProfile[]>("/api/report-form-profiles"),
		get: (operationCode: string) => request<ReportFormProfile>(`/api/report-form-profiles/${encodeURIComponent(operationCode)}`),
		save: (input: Omit<ReportFormProfile, "updatedAt">) => post<ReportFormProfile>("/api/report-form-profiles", input)
	},
	planning: {
		dispatchRecommendations: (operationCode?: string, supervisorCode?: string, routeType?: RouteType) => {
			const params = new URLSearchParams();
			if (operationCode) params.set("operationCode", operationCode);
			if (supervisorCode) params.set("supervisorCode", supervisorCode);
			if (routeType) params.set("routeType", routeType);
			const query = params.size ? `?${params}` : "";
			return request<DispatchRecommendation>(`/api/planning/dispatch-recommendations${query}`);
		}
  },
  trace: {
    order: (id: string) => request<OrderTrace>(`/api/trace/orders/${id}`)
  },
  quality: {
    list: () => request<QualityInspection[]>("/api/quality/inspections"),
    inspect: (input: {
      operationId: string;
      taskId: string;
      inspectedQuantity: number;
      acceptedQuantity: number;
      rejectedQuantity: number;
      defectCode?: string;
      inspectorCode: string;
      remark?: string;
    }) => post<{ inspection: QualityInspection; duplicate: boolean }>(
      "/api/quality/inspections",
      input
    ),
    dispose: (
      id: string,
      input: { decision: string; quantity: number; reason: string; decidedBy: string }
    ) => post(`/api/quality/inspections/${id}/dispositions`, input)
  },
  inventory: {
    balances: (viewerCode: string) => request<InventoryBalance[]>(`/api/inventory/balances?viewerCode=${encodeURIComponent(viewerCode)}`),
    movements: (viewerCode: string) => request<InventoryMovement[]>(`/api/inventory/movements?viewerCode=${encodeURIComponent(viewerCode)}`),
    balancePage: (input: { viewerCode: string; warehouseCode?: string; keyword?: string; unit?: string; stockStatus?: string; page?: number; size?: number }) => {
      const params = new URLSearchParams({ viewerCode: input.viewerCode, page: String(input.page ?? 0), size: String(input.size ?? 20) });
      if (input.warehouseCode) params.set("warehouseCode", input.warehouseCode);
      if (input.keyword) params.set("keyword", input.keyword);
      if (input.unit && input.unit !== "ALL") params.set("unit", input.unit);
      if (input.stockStatus && input.stockStatus !== "ALL") params.set("stockStatus", input.stockStatus);
      return request<PageResult<InventoryBalance>>(`/api/inventory/balances/query?${params.toString()}`);
    },
    movementPage: (input: { viewerCode: string; warehouseCode?: string; keyword?: string; movementType?: string; operatorCode?: string; fromDate?: string; toDate?: string; page?: number; size?: number }) => {
      const params = new URLSearchParams({ viewerCode: input.viewerCode, page: String(input.page ?? 0), size: String(input.size ?? 20) });
      if (input.warehouseCode) params.set("warehouseCode", input.warehouseCode);
      if (input.keyword) params.set("keyword", input.keyword);
      if (input.movementType && input.movementType !== "ALL") params.set("movementType", input.movementType);
      if (input.operatorCode && input.operatorCode !== "ALL") params.set("operatorCode", input.operatorCode);
      if (input.fromDate) params.set("fromDate", input.fromDate);
      if (input.toDate) params.set("toDate", input.toDate);
      return request<PageResult<InventoryMovement>>(`/api/inventory/movements/query?${params.toString()}`);
    },
    move: (input: {
      operationId: string;
      warehouseCode: string;
      itemCode: string;
      itemName: string;
      unit: string;
      movementType: string;
      quantity: number;
      referenceType?: string;
      referenceNo?: string;
      operatorCode: string;
      remark?: string;
    }) => post<{ movement: InventoryMovement; duplicate: boolean }>(
      "/api/inventory/movements",
      input
    ),
    createExport: (input: { operationId: string; viewerCode: string; warehouseCode?: string; keyword?: string; movementType?: string; operatorCode?: string; fromDate?: string; toDate?: string }) =>
      post<InventoryExportJob>("/api/inventory/exports", input),
    exportStatus: (id: string, viewerCode: string) =>
      request<InventoryExportJob>(`/api/inventory/exports/${encodeURIComponent(id)}?viewerCode=${encodeURIComponent(viewerCode)}`),
    exportDownload: (id: string, viewerCode: string) =>
      download(`/api/inventory/exports/${encodeURIComponent(id)}/download?viewerCode=${encodeURIComponent(viewerCode)}`)
  },
  piecework: {
    rates: () => request<PieceworkRate[]>("/api/piecework/rates"),
    entries: (viewerCode?: string) => request<PieceworkEntry[]>(`/api/piecework/entries${viewerCode ? `?viewerCode=${encodeURIComponent(viewerCode)}` : ""}`),
		payrollExport: (viewerCode: string, month: string) =>
			download(`/api/piecework/exports/payroll?viewerCode=${encodeURIComponent(viewerCode)}&month=${encodeURIComponent(month)}`),
		workerPayrollExport: (viewerCode: string, workerCode: string, month: string) =>
			download(`/api/piecework/exports/worker-payroll?viewerCode=${encodeURIComponent(viewerCode)}&workerCode=${encodeURIComponent(workerCode)}&month=${encodeURIComponent(month)}`),
		workerPayroll: (viewerCode: string, workerCode: string, month: string) =>
			request<WorkerPayrollDetail[]>(`/api/piecework/worker-payroll?viewerCode=${encodeURIComponent(viewerCode)}&workerCode=${encodeURIComponent(workerCode)}&month=${encodeURIComponent(month)}`),
		workerOutputExport: (viewerCode: string, workerCode: string, month: string, routeType?: RouteType) => {
			const route = routeType ? `&routeType=${encodeURIComponent(routeType)}` : "";
			return download(`/api/piecework/exports/worker-output?viewerCode=${encodeURIComponent(viewerCode)}&workerCode=${encodeURIComponent(workerCode)}&month=${encodeURIComponent(month)}${route}`);
		},
		workerOutput: (viewerCode: string, workerCode: string, month: string, routeType?: RouteType) => {
			const route = routeType ? `&routeType=${encodeURIComponent(routeType)}` : "";
			return request<WorkerOutputDetail[]>(`/api/piecework/worker-output?viewerCode=${encodeURIComponent(viewerCode)}&workerCode=${encodeURIComponent(workerCode)}&month=${encodeURIComponent(month)}${route}`);
		},
    createRate: (input: {
		  supervisorCode: string;
      operationCode: string;
      operationName: string;
      routeType: RouteType;
      productCode?: string;
      productName?: string;
      version: string;
      settlementUnit?: SettlementUnit;
      unitRate: number;
      effectiveFrom: string;
    }) => post<PieceworkRate>("/api/piecework/rates", input),
    createEntry: (input: {
      operationId: string;
      taskId: string;
      workerCode: string;
      recordedBy?: string;
      quantity: number;
    }) => post<{ entry: PieceworkEntry; duplicate: boolean }>("/api/piecework/entries", input),
    confirm: (id: string, supervisorCode: string) =>
      post<PieceworkEntry>(`/api/piecework/entries/${id}/confirmation`, { supervisorCode })
  },
  outsourcing: {
    suppliers: () => request<OutsourcingSupplier[]>("/api/outsourcing/suppliers"),
    orders: () => request<OutsourcingOrder[]>("/api/outsourcing/orders"),
		readyProductionTasks: (operatorCode: string) => request<Task[]>(`/api/outsourcing/ready-production-tasks?operatorCode=${encodeURIComponent(operatorCode)}`),
    createSupplier: (input: {
      code: string;
      name: string;
      contactName?: string;
      contactPhone?: string;
    }) => post<OutsourcingSupplier>("/api/outsourcing/suppliers", input),
    createOrder: (input: {
      orderNo: string;
      supplierId: string;
      itemCode: string;
      itemName: string;
      quantity: number;
      unit: string;
      dueDate?: string;
      remark?: string;
			planningTaskId?: string;
    }) => post<OutsourcingOrder>("/api/outsourcing/orders", input),
    transition: (
      id: string,
      input: {
        nextStatus: string;
        receivedQuantity?: number;
        note?: string;
        operatorCode: string;
      }
    ) => post<OutsourcingOrder>(`/api/outsourcing/orders/${id}/milestones`, input)
  },
  reporting: {
    dashboard: () => request<ManagementDashboard>("/api/reporting/dashboard")
  },
	 sales: {
		performance: (viewerCode: string, period: string, salesOwner?: string) => {
			const params = new URLSearchParams({ viewerCode, period });
			if (salesOwner?.trim()) params.set("salesOwner", salesOwner.trim());
			return request<SalesPerformance>(`/api/sales/performance?${params.toString()}`);
		}
	 },
  workflows: {
    list: () => request<WorkflowRequest[]>("/api/workflows"),
		definitions: () => request<WorkflowDefinition[]>("/api/workflows/definitions"),
		createDefinition: (input: { workflowType: string; name: string; description?: string; requiredApprovals: number; approverRoles: string[]; createdBy: string }) => post<WorkflowDefinition>("/api/workflows/definitions", input),
    submit: (input: {
      workflowType: string;
      businessKey: string;
      title: string;
      requesterCode: string;
      requiredApprovals: number;
      payload?: string;
    }) => post<WorkflowRequest>("/api/workflows", input),
    act: (id: string, input: { action: string; actorCode: string; comment?: string }) =>
      post<WorkflowRequest>(`/api/workflows/${id}/actions`, input)
  },
  configurations: {
    list: () => request<ConfigurationPackage[]>("/api/configurations"),
    create: (input: {
      configType: string;
      name: string;
      version: string;
      content: string;
      createdBy: string;
    }) => post<ConfigurationPackage>("/api/configurations", input),
    publish: (id: string, operatorCode: string) =>
      post<ConfigurationPackage>(`/api/configurations/${id}/publication`, { operatorCode }),
    rollback: (id: string, operatorCode: string) =>
      post<ConfigurationPackage>(`/api/configurations/${id}/rollback`, { operatorCode })
  },
  organization: {
    units: () => request<OrganizationUnit[]>("/api/organization/units"),
    members: () => request<OrganizationMember[]>("/api/organization/members"),
    createUnit: (input: {
      code: string;
      name: string;
      unitType: string;
      parentCode?: string;
    }) => post<OrganizationUnit>("/api/organization/units", input),
    createMember: (input: {
      employeeCode: string;
      name: string;
      unitCode: string;
      roleCode: string;
    }) => post<OrganizationMember>("/api/organization/members", input),
    maintainMember: (employeeCode: string, input: {
      name: string;
      unitCode: string;
      roleCode: string;
      active: boolean;
    }) => post<OrganizationMember>(`/api/organization/members/${employeeCode}/maintenance`, input)
  },
  resources: {
    list: () => request<ResourceAsset[]>("/api/resources"),
    assetPage: (input: { assetType?: string; keyword?: string; custodyStatus?: string; ownershipType?: string; status?: string; locationCode?: string; page?: number; size?: number }) => {
      const params = new URLSearchParams({ page: String(input.page ?? 0), size: String(input.size ?? 20) });
      if (input.assetType) params.set("assetType", input.assetType);
      if (input.keyword) params.set("keyword", input.keyword);
      if (input.custodyStatus && input.custodyStatus !== "ALL") params.set("custodyStatus", input.custodyStatus);
      if (input.ownershipType && input.ownershipType !== "ALL") params.set("ownershipType", input.ownershipType);
      if (input.status && input.status !== "ALL") params.set("status", input.status);
      if (input.locationCode && input.locationCode !== "ALL") params.set("locationCode", input.locationCode);
      return request<PageResult<ResourceAsset>>(`/api/resources/query?${params.toString()}`);
    },
    occupations: () => request<ResourceOccupation[]>("/api/resources/occupations"),
    register: (input: {
      assetCode: string;
      assetName: string;
        assetType: string;
        locationCode?: string;
        lifeLimit?: number;
		ownershipType?: "COMPANY_OWNED" | "CUSTOMER_OWNED";
		ownerName?: string;
    }) => post<ResourceAsset>("/api/resources", input),
    occupy: (
      id: string,
      input: { businessKey: string; operatorCode: string; note?: string }
    ) => post<ResourceAsset>(`/api/resources/${id}/occupations`, input),
    release: (id: string, input: { operatorCode: string; consumeLife: boolean }) =>
      post<ResourceAsset>(`/api/resources/${id}/release`, input),
		updateMoldImage: (id: string, moldImageUrl?: string) => post<ResourceAsset>(`/api/resources/${id}/mold-image`, { moldImageUrl })
  },
  assetQrs: {
    list: () => request<AssetQrLabel[]>("/api/asset-qr-codes"),
    generate: (input: { intendedAssetType: "MOLD" | "CARRIER"; count: number; createdBy: string }) =>
      post<AssetQrLabel[]>("/api/asset-qr-codes/batch", input),
    issue: (assetId: string, actorCode: string) =>
      post<AssetQrLabel>(`/api/asset-qr-codes/assets/${assetId}/issue`, { actorCode }),
    reprint: (id: string, actorCode: string) =>
      post<AssetQrLabel>(`/api/asset-qr-codes/${id}/reprint`, { actorCode }),
    bind: (input: { scannedValue: string; assetId: string; boundBy: string }) =>
      post<AssetQrLabel>("/api/asset-qr-codes/bind", input),
    exportEzcadVariableData: (labelIds: string[], actorCode: string) => {
      const params = new URLSearchParams({ actorCode });
      labelIds.forEach((id) => params.append("labelIds", id));
      return download(`/api/asset-qr-codes/exports/ezcad-variable-data?${params}`);
    },
    recordMoldDxfExport: (labelIds: string[], actorCode: string) =>
      post<AssetQrLabel[]>("/api/asset-qr-codes/exports/dxf", { labelIds, actorCode }),
    recordMoldPngExport: (labelIds: string[], actorCode: string) =>
      post<AssetQrLabel[]>("/api/asset-qr-codes/exports/png", { labelIds, actorCode })
  },
  cartTransfers: {
    list: () => request<CartTransfer[]>("/api/cart-transfers"),
    readySources: () => request<CartSource[]>("/api/cart-transfers/ready-sources"),
    load: (input: { sourceTaskId: string; cartCode: string; quantity: number; loadPhotoUrl?: string; loadedBy: string; operationId?: string; deviceCode?: string; workstationCode?: string }) =>
      post<CartTransfer>("/api/cart-transfers/load", input),
    receive: (id: string, input: { receivedQuantity: number; receivePhotoUrl?: string; exceptionReason?: string; receivedBy: string; operationId?: string; deviceCode?: string; workstationCode?: string }) =>
      post<CartTransfer>(`/api/cart-transfers/${id}/receive`, input)
  },
	handoffExceptions: {
		list: (includeResolved = false) => request<HandoffException[]>(`/api/handoff-exceptions?includeResolved=${includeResolved}`),
		page: (input: { includeResolved?: boolean; keyword?: string; resolutionStatus?: string; page?: number; size?: number }) => {
			const params = new URLSearchParams({ includeResolved: String(input.includeResolved ?? false), page: String(input.page ?? 0), size: String(input.size ?? 20) });
			if (input.keyword) params.set("keyword", input.keyword);
			if (input.resolutionStatus) params.set("resolutionStatus", input.resolutionStatus);
			return request<import("./types").PageResult<HandoffException>>(`/api/handoff-exceptions/query?${params}`);
		},
		assign: (sourceType: "HANDOFF" | "CART", id: string, ownerCode: string, assignedBy: string) =>
			post<HandoffException>(`/api/handoff-exceptions/${sourceType}/${id}/assign`, { ownerCode, assignedBy }),
		resolve: (sourceType: "HANDOFF" | "CART", id: string, resolutionNote: string, resolvedBy: string) =>
			post<HandoffException>(`/api/handoff-exceptions/${sourceType}/${id}/resolve`, { resolutionNote, resolvedBy })
	},
	postTreatment: {
		decisions: (supervisorCode: string) => request<PostTreatmentDecision[]>(`/api/post-treatment/decisions?supervisorCode=${encodeURIComponent(supervisorCode)}`),
		decide: (input: { sourceTaskId: string; destination: "IN_HOUSE" | "OUTSOURCE" | "DIRECT_FINISHED" | "FINISHED_GOODS_STORAGE"; processCodes?: string[]; processSummary?: string; supplierId?: string; warehouseCode?: string; note?: string; decidedBy: string }) =>
			post<PostTreatmentDecision>("/api/post-treatment/decisions", input)
	},
	furnaceBatches: {
		list: () => request<FurnaceBatch[]>("/api/furnace-batches"),
		create: (input: { operationCode: "DEWAX" | "POURING"; furnaceAssetId?: string; materialBatch?: string; chargeQuantity: number; targetTemperature?: number; actualTemperature?: number; pressureMpa?: number; taskIds: string[]; note?: string; createdBy: string }) =>
			post<FurnaceBatch>("/api/furnace-batches", input),
		complete: (id: string, input: { actualTemperature?: number; pressureMpa?: number; note?: string; completedBy: string }) =>
			post<FurnaceBatch>(`/api/furnace-batches/${id}/complete`, input)
	},
  scheduling: {
    queue: (lineCode?: string, operationCode?: string) => {
      const params = new URLSearchParams();
      if (lineCode) params.set("lineCode", lineCode);
      if (operationCode) params.set("operationCode", operationCode);
      const query = params.size ? `?${params}` : "";
      return request<ScheduleQueueItem[]>(`/api/scheduling/queue${query}`);
    },
    updateRank: (taskId: string, manualRank: number) =>
      post<ScheduleQueueItem>(`/api/scheduling/queue/${taskId}/rank`, { manualRank })
  },
  	molds: {
		locations: () => request<import("./types").MoldStorageLocation[]>("/api/factory/mold-requests/locations"),
		createLocation: (input: { locationCode: string; locationName: string; capacity: number; operatorCode: string }) => post<import("./types").MoldStorageLocation>("/api/factory/mold-requests/locations", input),
		updateLocation: (code: string, input: { locationName: string; capacity: number; active: boolean; operatorCode: string }) => post<import("./types").MoldStorageLocation>(`/api/factory/mold-requests/locations/${encodeURIComponent(code)}`, input),
  		lifecycle: () => request<MoldLifecycle[]>('/api/molds'),
		due: () => request<MoldLifecycle[]>('/api/molds/due'),
		history: (id: string) => request<MoldMaintenanceRecord[]>(`/api/molds/${id}/maintenance-records`),
		configure: (id: string, input: { lifeLimit?: number; maintenanceIntervalDays?: number; nextMaintenanceDate?: string }) => post<MoldLifecycle>(`/api/molds/${id}/configuration`, input),
		recordMaintenance: (id: string, input: { recordType: "REPAIR" | "MAINTENANCE" | "INSPECTION"; description: string; serviceProvider?: string; performedBy: string; nextMaintenanceDate?: string }) => post<MoldMaintenanceRecord>(`/api/molds/${id}/maintenance-records`, input),
		lock: (id: string, reason: string, operatorCode: string) => post<MoldLifecycle>(`/api/molds/${id}/lock`, { reason, operatorCode }),
		unlock: (id: string, resolutionNote: string, operatorCode: string) => post<MoldLifecycle>(`/api/molds/${id}/unlock`, { resolutionNote, operatorCode }),
    list: () => request<MoldRequest[]>("/api/factory/mold-requests"),
		orderSelections: () => request<OrderMoldSelection[]>("/api/factory/mold-requests/order-selections"),
		externalMovements: () => request<MoldExternalMovement[]>("/api/factory/mold-requests/external-movements"),
		maintenanceOverdue: () => request<MoldExternalMovement[]>("/api/factory/mold-requests/maintenance-overdue"),
    selectForOrderLine: (input: { orderId: string; orderLineId: string; moldAssetId: string; selectedBy: string }) =>
      post<OrderMoldSelection>("/api/factory/mold-requests/order-selections", input),
		planForOrderLine: (input: { orderId: string; orderLineId: string; selectionStatus: "CUSTOM_MOLD_TO_RECEIVE" | "CUSTOMER_DELIVERY_PENDING"; pendingReason?: string; selectedBy: string }) =>
			post<OrderMoldSelection>("/api/factory/mold-requests/order-mold-plans", input),
		locationSuggestions: () => request<MoldLocationSuggestion[]>("/api/factory/mold-requests/location-suggestions"),
		receive: (input: { scannedValue?: string; assetCode?: string; assetName: string; locationCode?: string; lifeLimit?: number; ownershipType: "COMPANY_OWNED" | "CUSTOMER_OWNED"; ownerName?: string; operatorCode: string; orderLineId?: string }) =>
			post<ResourceAsset>("/api/factory/mold-requests/receipts", input),
		checkOutExternally: (input: { moldAssetId: string; reasonCode: "CUSTOMER_RECALL" | "MAINTENANCE" | "OTHER"; reasonNote?: string; counterpartyName?: string; expectedReturnDate?: string; operatorCode: string }) =>
			post<MoldExternalMovement>("/api/factory/mold-requests/external-movements", input),
		returnFromExternal: (id: string, operatorCode: string) =>
			post<MoldExternalMovement>(`/api/factory/mold-requests/external-movements/${id}/return`, { operatorCode }),
    request: (input: { moldAssetId?: string; productCode: string; requestedBy: string }) =>
      post<MoldRequest>("/api/factory/mold-requests", input),
    requestForWaxTask: (taskId: string, input: { moldAssetId: string; supervisorCode: string; intendedWorkerCode: string }) =>
      post<MoldRequest>(`/api/factory/mold-requests/tasks/${taskId}`, input),
    approve: (id: string, engineerCode: string) =>
      post<MoldRequest>(`/api/factory/mold-requests/${id}/approval`, { engineerCode }),
    issue: (id: string, warehouseCode: string, operatorCode: string, recipientWorkerCode?: string) =>
      post<MoldRequest>(`/api/factory/mold-requests/${id}/issue`, { warehouseCode, operatorCode, recipientWorkerCode }),
    returnMold: (id: string, operatorCode: string) =>
      post<MoldRequest>(`/api/factory/mold-requests/${id}/return`, { operatorCode })
  },
  labor: {
		paperSheets: (supervisorCode: string) => request<PaperReportSheet[]>(`/api/labor/paper-sheets?supervisorCode=${encodeURIComponent(supervisorCode)}`),
		createPaperSheet: (input: { taskId: string; workerCode: string; reportKind: "QUANTITY" | "HOURS"; goodQuantity?: number; scrapQuantity?: number; hours?: number; note?: string; enteredBy: string; paperFormNo?: string; paperImageUrl?: string }) => post<PaperReportSheet>("/api/labor/paper-sheets", input),
		approvePaperSheet: (id: string, reviewerCode: string) => post<PaperReportSheet>(`/api/labor/paper-sheets/${id}/approval`, { reviewerCode }),
		rejectPaperSheet: (id: string, reviewerCode: string, reason: string) => post<PaperReportSheet>(`/api/labor/paper-sheets/${id}/rejection`, { reviewerCode, reason }),
    reportManualShellByScan: (input: { scannedValue: string; layerCount: number; dryingMinutes: number; quantity: number; operatorCode: string; note?: string; nextAction?: "WAIT_NEXT_LAYER" | "FLOW_TO_NEXT"; photoUrl?: string }) =>
      post("/api/labor/manual-shell/scan-progress", input),
    shellRecords: (taskId: string) => request<ShellRecord[]>(`/api/labor/shell-records?taskId=${encodeURIComponent(taskId)}`),
		shellSummaries: (taskIds: string[]) => request<import("./types").ShellProgressSummary[]>(`/api/labor/shell-records/summary?${taskIds.map((taskId) => `taskId=${encodeURIComponent(taskId)}`).join("&")}`)
  },
	notifications: {
    list: (recipientCode: string) =>
      request<NotificationItem[]>(
        `/api/notifications?recipientCode=${encodeURIComponent(recipientCode)}`
      ),
    create: (input: {
      recipientCode: string;
      category: string;
      title: string;
      content: string;
      businessLink?: string;
    }) => post<NotificationItem>("/api/notifications", input),
    markRead: (id: string, recipientCode: string) =>
      post<NotificationItem>(`/api/notifications/${id}/read`, { recipientCode })
	},
	scanEvents: {
		record: (input: { operationId: string; entityType: "TASK" | "ASSET" | "BATCH"; entityId: string; intent: string; operatorCode: string; scannedValue: string; deviceCode?: string; workstationCode?: string }) =>
			post<void>("/api/scan-events", input),
		resolve: (value: string) => request<import("./types").ScanResolution>(`/api/scans/resolve?value=${encodeURIComponent(value)}`)
	},
	productionAlerts: {
		list: (recipientCode: string) => request<ProductionShortageAlert[]>(`/api/production-alerts?recipientCode=${encodeURIComponent(recipientCode)}`)
	},
	manualShellDryingAlerts: {
		list: (recipientCode: string) => request<import("./types").ManualShellDryingAlert[]>(`/api/manual-shell-drying-alerts?recipientCode=${encodeURIComponent(recipientCode)}`)
	},
  integration: {
    list: () => request<IntegrationJob[]>("/api/integration/jobs"),
    submit: (input: {
      operationId: string;
      interfaceCode: string;
      businessKey: string;
      direction: string;
      payload: string;
    }) => post<{ job: IntegrationJob; duplicate: boolean }>("/api/integration/jobs", input),
    succeed: (id: string) => post<IntegrationJob>(`/api/integration/jobs/${id}/success`),
    fail: (id: string, error: string) =>
      post<IntegrationJob>(`/api/integration/jobs/${id}/failure`, { error }),
    retry: (id: string) => post<IntegrationJob>(`/api/integration/jobs/${id}/retry`)
  },
  operations: {
    overview: () => request<OperationsOverview>("/api/operations/overview")
  },
  access: {
    users: () => request<AccessUser[]>("/api/access/users"),
    roles: () => request<AccessRole[]>("/api/access/roles"),
    permissions: () => request<AccessPermission[]>("/api/access/permissions"),
    setUserRoles: (employeeCode: string, roleCodes: string[]) =>
      post<AccessUser>(`/api/access/users/${employeeCode}/roles`, { roleCodes }),
		initializeAccount: (employeeCode: string, input: { temporaryPassword: string; forcePasswordChange: boolean }) =>
			post<{ initialized: boolean }>(`/api/access/users/${employeeCode}/password-reset`, input),
		userScopes: (employeeCode: string) => request<UserDataScope>(`/api/access/users/${employeeCode}/scopes`),
		setUserScopes: (employeeCode: string, input: Omit<UserDataScope, "employeeCode">) =>
			post<UserDataScope>(`/api/access/users/${employeeCode}/scopes`, input),
    setRolePermissions: (roleCode: string, permissionCodes: string[]) =>
      post<AccessRole>(`/api/access/roles/${roleCode}/permissions`, { permissionCodes }),
    createRole: (input: { code: string; name: string; description: string }) =>
      post<AccessRole>("/api/access/roles", input)
  },
  documents: {
    options: (actorCode: string) =>
      request<DocumentOption[]>(`/api/documents/options?actorCode=${encodeURIComponent(actorCode)}`),
    preview: (input: {
      documentType: DocumentType;
      entityId?: string;
      actorCode: string;
      selectedFields: string[];
      outputType?: DocumentOutput;
    }) => post<DocumentPreview>("/api/documents/previews", input),
    audits: (actorCode: string) =>
      request<DocumentAudit[]>(`/api/documents/audits?actorCode=${encodeURIComponent(actorCode)}`)
  },
  sops: {
    list: () => request<OperationSop[]>("/api/sops"),
    get: (operationCode: string) =>
      request<OperationSop>(`/api/sops/${encodeURIComponent(operationCode)}`),
    publish: (input: {
      operationCode: string;
      operationName: string;
      version: string;
      safetyNotice: string;
      preparationNote: string;
      steps: Array<{ title: string; instruction: string }>;
      qualityPoints: string[];
      keyParameters: Array<{ name: string; value: string }>;
      updatedBy: string;
    }) => post<OperationSop>("/api/sops", input)
  },
  fulfillment: {
    lots: (viewerCode: string) => request<FinishedGoodsLot[]>(`/api/fulfillment/lots?viewerCode=${encodeURIComponent(viewerCode)}`),
    deliveries: (viewerCode: string) => request<DeliveryOrder[]>(`/api/fulfillment/deliveries?viewerCode=${encodeURIComponent(viewerCode)}`),
		pendingReceipts: (viewerCode: string) => request<PendingFinishedGoodsReceipt[]>(`/api/fulfillment/receipts?viewerCode=${encodeURIComponent(viewerCode)}`),
    lotPage: (input: { viewerCode: string; keyword?: string; availability?: string; warehouseCode?: string; page?: number; size?: number }) => {
      const params = new URLSearchParams({ viewerCode: input.viewerCode, page: String(input.page ?? 0), size: String(input.size ?? 20) });
      if (input.keyword) params.set("keyword", input.keyword);
      if (input.availability && input.availability !== "ALL") params.set("availability", input.availability);
      if (input.warehouseCode && input.warehouseCode !== "ALL") params.set("warehouseCode", input.warehouseCode);
      return request<PageResult<FinishedGoodsLot>>(`/api/fulfillment/lots/query?${params.toString()}`);
    },
    deliveryPage: (input: { viewerCode: string; keyword?: string; status?: string; page?: number; size?: number }) => {
      const params = new URLSearchParams({ viewerCode: input.viewerCode, page: String(input.page ?? 0), size: String(input.size ?? 20) });
      if (input.keyword) params.set("keyword", input.keyword);
      if (input.status && input.status !== "ALL") params.set("status", input.status);
      return request<PageResult<DeliveryOrder>>(`/api/fulfillment/deliveries/query?${params.toString()}`);
    },
		summary: (viewerCode: string) => request<{ availableQuantity: number; pendingCount: number; inTransitCount: number; deliveredCount: number }>(`/api/fulfillment/summary?viewerCode=${encodeURIComponent(viewerCode)}`),
    registerLot: (input: {
      operationId: string;
      taskId: string;
      quantity: number;
      warehouseCode: string;
      registeredBy: string;
    }) =>
      post<{ lot: FinishedGoodsLot; duplicate: boolean }>("/api/fulfillment/lots", input),
    createDelivery: (input: {
      orderId: string;
      lotId: string;
      quantity: number;
      recipientName: string;
      deliveryAddress: string;
      createdBy: string;
    }) => post<DeliveryOrder>("/api/fulfillment/deliveries", input),
    transition: (
      id: string,
      input: {
        nextStatus: string;
        operatorCode: string;
        carrier?: string;
        trackingNo?: string;
        note?: string;
      }
    ) => post<DeliveryOrder>(`/api/fulfillment/deliveries/${id}/transitions`, input)
  }
};

export interface AuthSession {
	user: AccessUser;
	mustChangePassword: boolean;
}
