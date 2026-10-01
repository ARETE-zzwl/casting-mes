import { FormEvent, useState } from "react";
import { BellRing, Check, Clock3, Factory, Plus, Search, TimerReset, Trash2 } from "lucide-react";
import { Link } from "react-router-dom";
import { api } from "../api";
import {
  EmptyState,
  ErrorNotice,
  Field,
  formatDate,
  formatQuantity,
  LoadingState,
  Modal,
  PageHeader,
  StatusBadge,
  SubmitActions
} from "../components/ui";
import { useAsyncData } from "../hooks";
import type { AccessUser, Order, OrderPriority, RouteType, Task } from "../types";

type DraftMoldStatus = "IN_STOCK" | "CUSTOM_MOLD_TO_RECEIVE" | "CUSTOMER_DELIVERY_PENDING";
type SalesPriceUnit = "TON" | "KG" | "PCS" | "SET" | "EA";
type DraftLine = { key: string; productId: string; productSearch: string; productMaterial: string; quantity: number; unit: string; salesUnitPrice: number; salesPriceUnit: SalesPriceUnit; moldStatus: DraftMoldStatus; moldAssetId: string; moldSearch: string; moldPlanNote: string };

function newDraftLine(): DraftLine {
  return { key: crypto.randomUUID(), productId: "", productSearch: "", productMaterial: "", quantity: 1, unit: "PCS", salesUnitPrice: 0, salesPriceUnit: "PCS", moldStatus: "IN_STOCK", moldAssetId: "", moldSearch: "", moldPlanNote: "" };
}

const defaultProcessCards: Record<RouteType, { summary: string; operations: Record<string, string> }> = {
	MID_TEMP_WAX: {
		summary: "默认工艺草案：工程师须按订单材质、图纸和客户要求复核后确认。",
		operations: {
			WAX_INJECTION: "蜡温 68C；注蜡压力 0.55MPa；保压 12s。",
			WAX_REPAIR: "按图纸修整分型线、缺陷和外观；不合格件隔离。",
			TREE_ASSEMBLY: "确认每树件数、树型和浇道；首树留样核对。",
			SHELL_BUILDING: "按工艺卡层数制壳；记录干燥时间与异常。",
			DEWAX: "记录炉号、装炉数量、温度和保温时间。",
			POURING: "复核材质批次、炉号和浇注温度后执行。",
			KNOCKOUT_CUTTING: "按分割标准脱壳、分割并隔离异常。",
			OPTIONAL_FINISHING: "按订单选择后处理方式，记录计件重量或数量。"
		}
	},
	LOW_TEMP_WAX: {
		summary: "默认工艺草案：工程师须按订单材质、图纸和客户要求复核后确认。",
		operations: {
			WAX_INJECTION: "低温蜡注蜡参数待按模具与产品复核。",
			WAX_REPAIR: "按图纸修整分型线、缺陷和外观；不合格件隔离。",
			TREE_ASSEMBLY: "确认每树件数、树型和浇道；首树留样核对。",
			MANUAL_SHELL_BUILDING: "按工艺卡层数手工制壳；记录干燥时间与异常。",
			DEWAX: "记录炉号、装炉数量、温度和保温时间。",
			POURING: "复核材质批次、炉号和浇注温度后执行。",
			KNOCKOUT_CUTTING: "按分割标准脱壳、分割并隔离异常。",
			OPTIONAL_FINISHING: "按订单选择后处理方式，记录计件重量或数量。"
		}
	},
	SAND_OUTSOURCE: {
		summary: "默认外协工艺草案：工程师须按客户图纸、材质和验收要求复核后确认。",
		operations: {}
	}
};

const routes: Array<{ value: RouteType; label: string }> = [
  { value: "MID_TEMP_WAX", label: "中温蜡线" },
  { value: "LOW_TEMP_WAX", label: "低温蜡线" },
  { value: "SAND_OUTSOURCE", label: "砂型外协" }
];

const routeNames: Record<RouteType, string> = Object.fromEntries(
  routes.map((route) => [route.value, route.label])
) as Record<RouteType, string>;

export function OrdersPage({ user }: { user: AccessUser }) {
	const hasPermission = (...permissions: string[]) => permissions.some((permission) => user.permissions.includes(permission));
	const [routeType, setRouteType] = useState<RouteType>("MID_TEMP_WAX");
  const state = useAsyncData(async () => {
		const [orders, customers, products, tasks, processCardTemplates, assets, moldSelections, moldLocations, productMolds, customerProductMolds] = await Promise.all([
			api.orders.list(),
			hasPermission("CUSTOMER_VIEW", "MASTERDATA_MANAGE") ? api.customers.list() : Promise.resolve([]),
			api.products.list(),
			api.tasks.list(),
			hasPermission("MASTERDATA_MANAGE", "TASK_DISPATCH") ? api.processCardTemplates.list(undefined, true) : Promise.resolve([]),
			hasPermission("ORDER_MOLD_SELECT", "TASK_DISPATCH", "MOLD_RECEIVE", "MOLD_WAREHOUSE_MANAGE") ? api.resources.list() : Promise.resolve([]),
			hasPermission("ORDER_MOLD_SELECT", "TASK_DISPATCH", "MOLD_RECEIVE", "MOLD_WAREHOUSE_MANAGE") ? api.molds.orderSelections() : Promise.resolve([]),
			hasPermission("MOLD_RECEIVE", "MOLD_WAREHOUSE_MANAGE") ? api.molds.locationSuggestions() : Promise.resolve([]),
			hasPermission("ORDER_MOLD_SELECT", "MASTERDATA_MANAGE", "TASK_DISPATCH", "MOLD_WAREHOUSE_MANAGE") ? api.productMolds.list() : Promise.resolve([]),
			hasPermission("ORDER_MOLD_SELECT", "MASTERDATA_MANAGE", "TASK_DISPATCH", "MOLD_WAREHOUSE_MANAGE") ? api.customerProductMolds.list() : Promise.resolve([])
		]);
		return { orders, customers, products, tasks, processCardTemplates, assets, moldSelections, moldLocations, productMolds, customerProductMolds };
  }, [user.employeeCode, user.permissions]);
  const [showCreate, setShowCreate] = useState(false);
	const [editingOrder, setEditingOrder] = useState<Order | null>(null);
	const [showCreateCustomer, setShowCreateCustomer] = useState(false);
	const [showCreateProduct, setShowCreateProduct] = useState(false);
	const [showCustomerPicker, setShowCustomerPicker] = useState(false);
	const [productPickerLineKey, setProductPickerLineKey] = useState<string | null>(null);
	const [moldPickerLineKey, setMoldPickerLineKey] = useState<string | null>(null);
	const [quickMoldReceiptLineKey, setQuickMoldReceiptLineKey] = useState<string | null>(null);
	const [quickMoldOwnership, setQuickMoldOwnership] = useState<"COMPANY_OWNED" | "CUSTOMER_OWNED">("COMPANY_OWNED");
	const [quickMoldLocation, setQuickMoldLocation] = useState("");
	const [quickMoldLocationMode, setQuickMoldLocationMode] = useState<"AUTO" | "MANUAL">("AUTO");
	const [pickerQuery, setPickerQuery] = useState("");
	const [customerSearch, setCustomerSearch] = useState("");
	const [selectedCustomerId, setSelectedCustomerId] = useState("");
	const [orderDrawingUrl, setOrderDrawingUrl] = useState<string | undefined>();
	const [contractAttachmentUrl, setContractAttachmentUrl] = useState<string | undefined>();
	const [productModelImageUrl, setProductModelImageUrl] = useState<string | undefined>();
	const [previewImage, setPreviewImage] = useState<{ url: string; label: string } | null>(null);
  const [lines, setLines] = useState<DraftLine[]>([
    newDraftLine()
  ]);
  const [pending, setPending] = useState(false);
  const [actionId, setActionId] = useState<string | null>(null);
	const [engineeringOrder, setEngineeringOrder] = useState<Order | null>(null);
	const [engineeringReturnOrder, setEngineeringReturnOrder] = useState<Order | null>(null);
	const [customerManagerReturnOrder, setCustomerManagerReturnOrder] = useState<Order | null>(null);
	const [generalManagerReturnOrder, setGeneralManagerReturnOrder] = useState<Order | null>(null);
	const [reviewTarget, setReviewTarget] = useState<{ order: Order; reviewer: "CUSTOMER_MANAGER" | "GENERAL_MANAGER" } | null>(null);
	const [engineeringTemplateId, setEngineeringTemplateId] = useState("");
	const [productProcessTemplateId, setProductProcessTemplateId] = useState("");
	const [useDefaultProcessCard, setUseDefaultProcessCard] = useState(false);
  const [error, setError] = useState<unknown>(null);

  function selectRoute(nextRoute: RouteType) {
    setRouteType(nextRoute);
    setLines((current) =>
      current.map((line) => ({
        ...line,
        productId: state.data?.products.some((product) => product.id === line.productId && product.routeType === nextRoute)
          ? line.productId
			  : "",
		productSearch: state.data?.products.some((product) => product.id === line.productId && product.routeType === nextRoute)
			? line.productSearch : "",
		productMaterial: state.data?.products.some((product) => product.id === line.productId && product.routeType === nextRoute)
			? line.productMaterial : ""
      }))
    );
  }

	function chooseCustomer(customerId: string, customerLabel: string) {
		setSelectedCustomerId(customerId);
		setCustomerSearch(customerLabel);
		setLines((current) => current.map((line) => ({ ...line, moldAssetId: "", moldSearch: "" })));
		setShowCustomerPicker(false);
	}

  async function createOrder(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
		if (!selectedCustomerId) {
			setError(new Error("请从客户检索结果中选择客户"));
			return;
		}
		if (lines.some((line) => !line.productId)) {
			setError(new Error("请从产品检索结果中选择每一行产品"));
			return;
		}
		if (lines.some((line) => !Number.isInteger(line.quantity) || line.quantity < 1)) {
			setError(new Error("订单产品数量必须为大于等于 1 的整数"));
			return;
		}
		if (lines.some((line) => !Number.isFinite(line.salesUnitPrice) || line.salesUnitPrice <= 0)) {
			setError(new Error("请为每个订单产品填写大于零的成交单价"));
			return;
		}
		if (new Set(lines.map((line) => line.productId)).size !== lines.length) {
			setError(new Error("同一产品请合并为一个订单行后填写总数量"));
			return;
		}
		if (routeType !== "SAND_OUTSOURCE" && lines.some((line) => line.moldStatus === "IN_STOCK" && !line.moldAssetId)) {
			setError(new Error("请选择在库模具，或将模具状态改为待定制入库/待客户送模"));
			return;
		}
    setPending(true);
    setError(null);
    try {
		const orderInput = {
		customerId: selectedCustomerId,
        priority: String(form.get("priority")) as OrderPriority,
        requestedDeliveryDate: String(form.get("requestedDeliveryDate") || "") || undefined,
        remark: String(form.get("remark") || "") || undefined,
			orderDrawingUrl,
			contractAttachmentUrl,
        lines: lines.map(({ productId, quantity, unit, salesUnitPrice, salesPriceUnit, productMaterial }) => ({
          productId,
          quantity,
			unit,
			salesUnitPrice,
			salesPriceUnit,
			productMaterial: productMaterial || undefined
        }))
      };
		const createdOrder = editingOrder
			? await api.orders.updateDraft(editingOrder.id, { ...orderInput, editorCode: user.employeeCode })
			: await api.orders.create({ ...orderInput, createdBy: user.employeeCode });
		if (routeType !== "SAND_OUTSOURCE") {
			await Promise.all(lines.map((line, index) => {
				const createdLine = createdOrder.lines[index];
				if (!createdLine) throw new Error("订单产品行创建失败，无法保存模具安排");
				return line.moldStatus === "IN_STOCK"
					? api.molds.selectForOrderLine({ orderId: createdOrder.id, orderLineId: createdLine.id, moldAssetId: line.moldAssetId, selectedBy: user.employeeCode })
					: api.molds.planForOrderLine({ orderId: createdOrder.id, orderLineId: createdLine.id, selectionStatus: line.moldStatus, pendingReason: line.moldPlanNote || undefined, selectedBy: user.employeeCode });
			}));
		}
      setShowCreate(false);
		setEditingOrder(null);
		setLines([newDraftLine()]);
		setSelectedCustomerId(""); setCustomerSearch(""); setOrderDrawingUrl(undefined); setContractAttachmentUrl(undefined);
      await state.reload();
    } catch (caught) {
      setError(caught);
    } finally {
      setPending(false);
    }
  }

	function openDraftEditor(order: Order) {
		const selectionByLine = new Map((state.data?.moldSelections ?? []).filter((selection) => selection.orderId === order.id).map((selection) => [selection.orderLineId, selection]));
		setRouteType(order.routeType);
		setSelectedCustomerId(order.customerId);
		setCustomerSearch(`${order.customerCode} · ${order.customerName}`);
		setOrderDrawingUrl(order.orderDrawingUrl ?? undefined);
		setContractAttachmentUrl(order.contractAttachmentUrl ?? undefined);
		setLines(order.lines.map((line) => {
			const selection = selectionByLine.get(line.id);
			return {
				key: crypto.randomUUID(), productId: line.productId, productSearch: `${line.productCode} · ${line.productName}`,
				productMaterial: line.productMaterial ?? "", quantity: Number(line.orderedQuantity), unit: line.unit,
				salesUnitPrice: Number(line.salesUnitPrice ?? 0), salesPriceUnit: line.salesPriceUnit ?? "PCS",
				moldStatus: selection?.selectionStatus === "CUSTOM_MOLD_TO_RECEIVE" ? "CUSTOM_MOLD_TO_RECEIVE" : selection?.selectionStatus === "CUSTOMER_DELIVERY_PENDING" ? "CUSTOMER_DELIVERY_PENDING" : "IN_STOCK",
				moldAssetId: selection?.moldAssetId ?? "", moldSearch: selection?.moldAssetCode ? `${selection.moldAssetCode} · ${selection.moldAssetName ?? ""}` : "",
				moldPlanNote: selection?.pendingReason ?? ""
			};
		}));
		setEditingOrder(order);
		setShowCreate(true);
	}

	async function submitForReview(order: Order) {
		setActionId(order.id); setError(null);
		try {
			await api.orders.submit(order.id, user.employeeCode);
			await state.reload();
		} catch (caught) { setError(caught); }
		finally { setActionId(null); }
	}

	async function createCustomer(event: FormEvent<HTMLFormElement>) {
		event.preventDefault();
		const form = new FormData(event.currentTarget);
		setPending(true);
		setError(null);
		try {
			await api.customers.create({ name: String(form.get("name")), contactName: String(form.get("contactName") || "") || undefined, contactPhone: String(form.get("contactPhone") || "") || undefined, salesOwner: String(form.get("salesOwner") || "") || undefined });
			setShowCreateCustomer(false);
			await state.reload();
		} catch (caught) {
			setError(caught);
		} finally {
			setPending(false);
		}
	}

	async function createProduct(event: FormEvent<HTMLFormElement>) {
		event.preventDefault();
		const form = new FormData(event.currentTarget);
		setPending(true);
		setError(null);
		try {
			await api.products.create({
				name: String(form.get("name")), routeType, routeVersion: String(form.get("routeVersion")),
				specification: String(form.get("specification")), material: String(form.get("material")),
				modelImageUrl: productModelImageUrl
			});
			setShowCreateProduct(false);
			setProductModelImageUrl(undefined);
			await state.reload();
		} catch (caught) {
			setError(caught);
		} finally {
			setPending(false);
		}
	}

	async function receiveQuickMold(event: FormEvent<HTMLFormElement>) {
		event.preventDefault();
		if (!quickMoldReceiptLine) return;
		const form = new FormData(event.currentTarget);
		setPending(true); setError(null);
		try {
			const ownershipType = String(form.get("ownershipType")) as "COMPANY_OWNED" | "CUSTOMER_OWNED";
			const asset = await api.molds.receive({
				assetCode: String(form.get("assetCode") || "") || undefined,
				assetName: String(form.get("assetName")),
				locationCode: quickMoldLocationMode === "AUTO" ? undefined : String(form.get("locationCode")),
				ownershipType,
				ownerName: ownershipType === "CUSTOMER_OWNED" ? String(form.get("ownerName") || "") || undefined : undefined,
				operatorCode: user.employeeCode
			});
			updateLine(quickMoldReceiptLine.key, { moldStatus: "IN_STOCK", moldAssetId: asset.id, moldSearch: `${asset.assetCode} · ${asset.assetName}` });
			setQuickMoldReceiptLineKey(null);
			await state.reload();
		} catch (caught) { setError(caught); } finally { setPending(false); }
	}

	async function uploadOrderDrawing(file: File) {
		setPending(true); setError(null);
		try { setOrderDrawingUrl((await api.files.upload("ORDER_DRAWING", file)).url); }
		catch (caught) { setError(caught); }
		finally { setPending(false); }
	}

	async function uploadContractAttachment(file: File) {
		setPending(true); setError(null);
		try { setContractAttachmentUrl((await api.files.upload("ORDER_CONTRACT", file)).url); }
		catch (caught) { setError(caught); }
		finally { setPending(false); }
	}

	async function uploadProductModel(file: File) {
		setPending(true); setError(null);
		try { setProductModelImageUrl((await api.files.upload("PRODUCT_MODEL", file)).url); }
		catch (caught) { setError(caught); }
		finally { setPending(false); }
	}

	async function remindEngineering(id: string) {
		setActionId(id);
		setError(null);
		try {
				await api.orders.remindEngineering(id, user.employeeCode);
			await state.reload();
    } catch (caught) {
      setError(caught);
    } finally {
      setActionId(null);
    }
  }

	async function reviewOrder(id: string, reviewer: "CUSTOMER_MANAGER" | "GENERAL_MANAGER") {
		setActionId(id); setError(null);
		try {
			if (reviewer === "CUSTOMER_MANAGER") await api.orders.reviewByCustomerManager(id, user.employeeCode);
			else await api.orders.reviewByGeneralManager(id, user.employeeCode);
			setReviewTarget(null);
			await state.reload();
		} catch (caught) { setError(caught); }
		finally { setActionId(null); }
	}

	async function releaseWithDefaultProcess(order: Order) {
		setActionId(order.id);
		setError(null);
		try {
			await api.orders.defaultProcessRelease(order.id, {
				supervisorCode: user.employeeCode,
				processCardVersion: `DEFAULT-${order.routeType}-V1`,
				engineeringParameters: "按已发布默认工艺受控投产；工程师后续确认订单专属参数。",
				engineeringOperationParameters: JSON.stringify(defaultProcessCards[order.routeType].operations)
			});
			await state.reload();
		} catch (caught) { setError(caught); }
		finally { setActionId(null); }
	}

	async function confirmEngineering(event: FormEvent<HTMLFormElement>) {
		event.preventDefault();
		if (!engineeringOrder) return;
		const form = new FormData(event.currentTarget);
		setPending(true);
		setError(null);
		try {
			await api.orders.confirmEngineering(engineeringOrder.id, {
				engineerCode: user.employeeCode,
				lines: engineeringOrder.lines.map((line) => ({
					orderLineId: line.id,
					processCardVersion: String(form.get(`processCardVersion-${line.id}`) || ""),
					engineeringParameters: String(form.get(`engineeringParameters-${line.id}`) || ""),
					engineeringOperationParameters: JSON.stringify({
						WAX_INJECTION: String(form.get(`waxInjectionParameters-${line.id}`) || ""),
						WAX_REPAIR: String(form.get(`waxRepairParameters-${line.id}`) || ""),
						TREE_ASSEMBLY: String(form.get(`treeAssemblyParameters-${line.id}`) || ""),
						SHELL_BUILDING: String(form.get(`shellParameters-${line.id}`) || ""),
						MANUAL_SHELL_BUILDING: String(form.get(`shellParameters-${line.id}`) || ""),
						DEWAX: String(form.get(`dewaxParameters-${line.id}`) || ""),
						POURING: String(form.get(`pouringParameters-${line.id}`) || ""),
						KNOCKOUT_CUTTING: String(form.get(`knockoutParameters-${line.id}`) || ""),
						OPTIONAL_FINISHING: String(form.get(`finishingParameters-${line.id}`) || "")
					})
				}))
			});
			setEngineeringOrder(null);
			await state.reload();
		} catch (caught) {
			setError(caught);
		} finally {
			setPending(false);
		}
	}

	async function returnForEngineering(event: FormEvent<HTMLFormElement>) {
		event.preventDefault();
		if (!engineeringReturnOrder) return;
		const reason = String(new FormData(event.currentTarget).get("reason") || "").trim();
		setPending(true); setError(null);
		try {
			await api.orders.returnForEngineering(engineeringReturnOrder.id, user.employeeCode, reason);
			setEngineeringReturnOrder(null);
			await state.reload();
		} catch (caught) { setError(caught); }
		finally { setPending(false); }
	}

	async function returnByCustomerManager(event: FormEvent<HTMLFormElement>) {
		event.preventDefault();
		if (!customerManagerReturnOrder) return;
		const reason = String(new FormData(event.currentTarget).get("reason") || "").trim();
		setPending(true); setError(null);
		try {
			await api.orders.returnByCustomerManager(customerManagerReturnOrder.id, user.employeeCode, reason);
			setCustomerManagerReturnOrder(null);
			await state.reload();
		} catch (caught) { setError(caught); }
		finally { setPending(false); }
	}

	async function returnByGeneralManager(event: FormEvent<HTMLFormElement>) {
		event.preventDefault();
		if (!generalManagerReturnOrder) return;
		const reason = String(new FormData(event.currentTarget).get("reason") || "").trim();
		setPending(true); setError(null);
		try {
			await api.orders.returnByGeneralManager(generalManagerReturnOrder.id, user.employeeCode, reason);
			setGeneralManagerReturnOrder(null);
			await state.reload();
		} catch (caught) { setError(caught); }
		finally { setPending(false); }
	}

  function updateLine(key: string, patch: Partial<DraftLine>) {
    setLines((current) =>
      current.map((line) => {
        if (line.key !== key) return line;
        const productChanged = patch.productId !== undefined && patch.productId !== line.productId;
        const preferred = productChanged && patch.productId && patch.moldAssetId === undefined ? preferredMold(patch.productId) : undefined;
        return {
          ...line,
          ...patch,
          moldAssetId: preferred?.id ?? (productChanged && patch.moldAssetId === undefined ? "" : patch.moldAssetId ?? line.moldAssetId),
          moldSearch: preferred
            ? `${preferred.assetCode} · ${preferred.assetName}`
            : productChanged && patch.moldSearch === undefined
              ? ""
              : patch.moldSearch ?? line.moldSearch
        };
      })
    );
  }

  if (state.loading) return <LoadingState label="正在加载客户订单" />;
  if (state.error) return <ErrorNotice error={state.error} onRetry={state.reload} />;

  const orders = (Array.isArray(state.data?.orders) ? state.data.orders : []).filter((order) => order.routeType === routeType);
  const customers = Array.isArray(state.data?.customers) ? state.data.customers : [];
  const products = Array.isArray(state.data?.products) ? state.data.products : [];
	const assets = Array.isArray(state.data?.assets) ? state.data.assets : [];
	const moldLocations = Array.isArray(state.data?.moldLocations) ? state.data.moldLocations : [];
	const moldSelections = Array.isArray(state.data?.moldSelections) ? state.data.moldSelections : [];
	const productMolds = Array.isArray(state.data?.productMolds) ? state.data.productMolds : [];
	const customerProductMolds = Array.isArray(state.data?.customerProductMolds) ? state.data.customerProductMolds : [];
	const tasks = Array.isArray(state.data?.tasks) ? state.data.tasks : [];
	const moldLocationByCode = new Map(moldLocations.map((location) => [location.locationCode, location]));
	function moldLocationLabel(mold: typeof assets[number]) {
		if (!mold.locationCode) return "库位未登记";
		const location = moldLocationByCode.get(mold.locationCode);
		return location ? `库位 ${mold.locationCode} · ${location.occupiedCount} / ${location.capacity}` : `库位 ${mold.locationCode}`;
	}
  const routeProducts = products.filter((product) => product.routeType === routeType);
	const selectedCustomer = customers.find((customer) => customer.id === selectedCustomerId);
	const inStockMolds = assets.filter((asset) => asset.assetType === "MOLD" && asset.status === "AVAILABLE" && asset.moldCustodyStatus === "IN_STOCK");
	const customerHistoryMoldIds = new Set(moldSelections.filter((selection) => selection.customerId === selectedCustomerId && selection.moldAssetId).map((selection) => selection.moldAssetId));
	function recommendedMolds(line: DraftLine) {
		const query = line.moldSearch.toLowerCase();
		const linkedMoldIds = new Set(productMolds.filter((relation) => relation.productId === line.productId).map((relation) => relation.moldAssetId));
		const customerProductMoldIds = new Set(customerProductMolds.filter((relation) => relation.customerId === selectedCustomerId && relation.productId === line.productId).map((relation) => relation.moldAssetId));
		return inStockMolds.filter((mold) => `${mold.assetCode} ${mold.assetName} ${mold.locationCode ?? ""} ${mold.ownerName ?? ""}`.toLowerCase().includes(query))
			.sort((left, right) => Number(customerProductMoldIds.has(right.id)) - Number(customerProductMoldIds.has(left.id))
			|| Number(linkedMoldIds.has(right.id)) - Number(linkedMoldIds.has(left.id))
			|| Number(customerHistoryMoldIds.has(right.id)) - Number(customerHistoryMoldIds.has(left.id))
			|| Number(right.ownershipType === "CUSTOMER_OWNED" && right.ownerName === selectedCustomer?.name) - Number(left.ownershipType === "CUSTOMER_OWNED" && left.ownerName === selectedCustomer?.name));
	}
	function preferredMold(productId: string) {
		const customerMatching = customerProductMoldsForLine({ productId });
		if (customerMatching.length === 1) return customerMatching[0];
		const productMatching = productMoldsForLine({ productId });
		return customerMatching.length === 0 && productMatching.length === 1 ? productMatching[0] : undefined;
	}
	function customerProductMoldsForLine(line: Pick<DraftLine, "productId">) {
		if (!selectedCustomerId || !line.productId) return [];
		return customerProductMolds
			.filter((relation) => relation.customerId === selectedCustomerId && relation.productId === line.productId)
			.map((relation) => inStockMolds.find((mold) => mold.id === relation.moldAssetId))
			.filter((mold): mold is typeof inStockMolds[number] => mold != null);
	}
	function productMoldsForLine(line: Pick<DraftLine, "productId">) {
		if (!line.productId) return [];
		const moldIds = new Set(productMolds.filter((relation) => relation.productId === line.productId).map((relation) => relation.moldAssetId));
		return inStockMolds.filter((mold) => moldIds.has(mold.id));
	}
	const productPickerLine = lines.find((line) => line.key === productPickerLineKey);
	const moldPickerLine = lines.find((line) => line.key === moldPickerLineKey);
	const quickMoldReceiptLine = lines.find((line) => line.key === quickMoldReceiptLineKey);
	const quickMoldReceiptProduct = quickMoldReceiptLine ? routeProducts.find((product) => product.id === quickMoldReceiptLine.productId) : undefined;
	const canReceiveMold = user.permissions.includes("MOLD_RECEIVE") || user.permissions.includes("MOLD_ISSUE");
	const normalizedPickerQuery = pickerQuery.trim().toLowerCase();
	const customerHistoryProductIds = new Set(selectedCustomerId ? orders
		.filter((order) => order.customerId === selectedCustomerId)
		.flatMap((order) => order.lines.map((line) => line.productId)) : []);
	const customerHistoryProducts = selectedCustomer
		? products.filter((product) => product.routeType === routeType && (customerHistoryProductIds.has(product.id)
			|| customerProductMolds.some((relation) => relation.customerId === selectedCustomer.id && relation.productId === product.id)))
		: [];
	const customerHistoryProductIdSet = new Set(customerHistoryProducts.map((product) => product.id));
	const pickerProducts = routeProducts.filter((product) =>
		`${product.code} ${product.name} ${product.specification ?? ""} ${product.material ?? ""}`.toLowerCase().includes(normalizedPickerQuery)
	).sort((left, right) => Number(customerHistoryProductIdSet.has(right.id)) - Number(customerHistoryProductIdSet.has(left.id))
		|| left.name.localeCompare(right.name, "zh-CN"));
	const pickerMolds = moldPickerLine
		? recommendedMolds(moldPickerLine).filter((mold) => `${mold.assetCode} ${mold.assetName} ${mold.locationCode ?? ""} ${mold.ownerName ?? ""}`.toLowerCase().includes(normalizedPickerQuery))
		: [];
	const pickerCustomers = customers.filter((customer) => `${customer.code} ${customer.name}`.toLowerCase().includes(normalizedPickerQuery));
	const isFrontDesk = user.roles.includes("FRONT_DESK_CLERK");
	const isEngineer = user.roles.includes("PROCESS_ENGINEER");
	const isCustomerManager = user.roles.includes("CUSTOMER_MANAGER");
	const isGeneralManager = user.roles.includes("GENERAL_MANAGER");
	const isSupervisor = user.roles.some((role) => ["PRODUCTION_MANAGER", "WORKSHOP_SUPERVISOR", "MID_WAX_SUPERVISOR", "LOW_WAX_SUPERVISOR", "MID_SHELL_SUPERVISOR", "LOW_SHELL_SUPERVISOR", "POST_PROCESS_SUPERVISOR", "FINISHING_SUPERVISOR"].includes(role));
	const engineeringTemplates = (state.data?.orders ?? []).filter((order) => order.id !== engineeringOrder?.id && order.routeType === engineeringOrder?.routeType && order.engineeringParameters && order.engineeringOperationParameters);
	const productProcessTemplates = Array.isArray(state.data?.processCardTemplates) ? state.data.processCardTemplates : [];
		const engineeringTemplate = engineeringTemplates.find((order) => order.id === engineeringTemplateId);
		const productProcessTemplate = productProcessTemplates.find((template) => template.id === productProcessTemplateId);
		const templateOperationParameters = productProcessTemplate
			? parseOperationParameters(productProcessTemplate.operationParameters)
			: useDefaultProcessCard && engineeringOrder
			? defaultProcessCards[engineeringOrder.routeType].operations
			: parseOperationParameters(engineeringTemplate?.engineeringOperationParameters);
		const templateOperationImages = productProcessTemplate
			? parseOperationImages(productProcessTemplate.operationParameters)
			: parseOperationImages(engineeringTemplate?.engineeringOperationParameters);
		const engineeringSummary = productProcessTemplate
			? productProcessTemplate.engineeringParameters
			: useDefaultProcessCard && engineeringOrder
			? defaultProcessCards[engineeringOrder.routeType].summary
			: engineeringTemplate?.engineeringParameters ?? "";
  const canCreate = isFrontDesk;
	const completedOrderIds = completedOrders(tasks);
  const activeOrderIds = new Set(
    tasks
      .filter((task) => task.status === "ASSIGNED" || task.status === "IN_PROGRESS")
      .map((task) => task.orderId)
  );
  const urgentOrders = orders.filter((order) => order.priority === "URGENT");
  const engineeringPendingOrders = orders.filter((order) => order.status === "DRAFT");
  const activeOrders = orders.filter((order) => activeOrderIds.has(order.id));

  return (
    <>
      <PageHeader
        title="客户订单"
        description="从客户需求建立可追溯的生产来源"
        action={
          <button
            className="button button-primary"
            type="button"
            disabled={!canCreate}
            onClick={() => { setEditingOrder(null); setLines([newDraftLine()]); setSelectedCustomerId(""); setCustomerSearch(""); setOrderDrawingUrl(undefined); setContractAttachmentUrl(undefined); setShowCreate(true); }}
          >
            <Plus aria-hidden="true" />新建订单
          </button>
        }
      />
      {!canCreate && (
        <div className="info-notice">
          创建订单前需要至少一个客户和一个产品。
          <Link to="/master-data">前往产品与路线</Link>
        </div>
      )}
      {error && <ErrorNotice error={error} />}
      <section className="order-workspace-summary" aria-label="当前产线订单概览">
        <div className="order-workspace-heading">
          <span>订单池</span>
          <strong>{routeNames[routeType]}</strong>
          <small>按当前产线独立管理订单、工单与生产批次</small>
        </div>
        <div className="order-workspace-metric"><Factory aria-hidden="true" /><div><span>订单总数</span><strong>{orders.length}</strong></div></div>
        <div className="order-workspace-metric active"><TimerReset aria-hidden="true" /><div><span>生产中</span><strong>{activeOrders.length}</strong></div></div>
        <div className="order-workspace-metric urgent"><Clock3 aria-hidden="true" /><div><span>加急订单</span><strong>{urgentOrders.length}</strong></div></div>
        <div className="order-workspace-metric pending"><BellRing aria-hidden="true" /><div><span>待工程确认</span><strong>{engineeringPendingOrders.length}</strong></div></div>
      </section>
	  <section className="filter-bar" aria-label="订单生产线">
		<div className="segmented-control" role="group" aria-label="选择订单生产线">
			{routes.map((route) => <button key={route.value} type="button" className={routeType === route.value ? "active" : ""} aria-pressed={routeType === route.value} onClick={() => selectRoute(route.value)}>{route.label}</button>)}
		</div>
		<span className="muted">一张订单仅能归属当前产线，产品、工单与排产队列不会跨线混排。</span>
	  </section>

      <section className="section-block">
        {orders.length === 0 ? (
          <EmptyState
            title="暂无客户订单"
            description={`产品与路线就绪后，在${routeNames[routeType]}创建首张订单。`}
            action={
              canCreate ? (
                <button className="button button-primary" onClick={() => setShowCreate(true)}>
                  <Plus aria-hidden="true" />新建订单
                </button>
              ) : undefined
            }
          />
        ) : (
          <>
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
				  <th>生产线</th>
                  <th>订单号</th>
                  <th>客户</th>
                  <th>产品与数量</th>
                  <th>优先级</th>
                  <th>状态</th>
                  <th>交付日期</th>
                  <th className="actions-cell">操作</th>
                </tr>
              </thead>
              <tbody>
                {orders.map((order) => (
					  <tr key={order.id} className={orderRowTone(order, completedOrderIds)}>
					<td><StatusBadge value={order.routeType} /></td>
                    <td className="primary-cell">{order.orderNo}</td>
                    <td>
                      <strong>{order.customerName}</strong>
                      <small>{order.customerCode}</small>
                    </td>
                    <td>
                      {order.lines.map((line) => (
                        <span className="line-summary" key={line.id}>
						  {line.productName} · {line.productMaterial ?? products.find((product) => product.id === line.productId)?.material ?? "材质未登记"} · {formatQuantity(line.orderedQuantity)} {line.unit}
                        </span>
                      ))}
                    </td>
					<td><StatusBadge value={order.priority} /></td>
                    <td><div className="order-status-cell"><StatusBadge value={order.status} /><span>{orderToneLabel(order, completedOrderIds)}</span></div></td>
                    <td>{formatDate(order.requestedDeliveryDate)}</td>
                    <td className="actions-cell">
					  <div className="order-document-actions">
					  {isFrontDesk && <Link className="text-link" to={`/trace?orderId=${order.id}`}>生产进度</Link>}
					  {order.orderDrawingUrl && <a className="text-link" href={order.orderDrawingUrl} target="_blank" rel="noreferrer">订单图纸</a>}
					  {order.contractAttachmentUrl && <a className="text-link" href={order.contractAttachmentUrl} target="_blank" rel="noreferrer">合同附件</a>}
					  {order.status === "RELEASED" && (
						<Link className="text-link" to={`/trace?orderId=${order.id}`}>追溯</Link>
					  )}
					  </div>
					  {(order.status === "DRAFT" || order.status === "SUBMITTED") && <small className="order-review-gate">{reviewGateLabel(order)}</small>}
					  {order.engineeringReturnReason && order.engineeringReturnResolvedAt == null && <small className="order-review-gate">工程退回：{order.engineeringReturnReason}</small>}
					  {order.customerManagerReturnReason && order.customerManagerReturnResolvedAt == null && <small className="order-review-gate">客户经理退回：{order.customerManagerReturnReason}</small>}
					  {order.generalManagerReturnReason && order.generalManagerReturnResolvedAt == null && <small className="order-review-gate">总经理退回：{order.generalManagerReturnReason}</small>}
					  {order.status === "DRAFT" && isFrontDesk && <><button className="button button-secondary button-small" disabled={actionId === order.id} onClick={() => openDraftEditor(order)}>编辑草稿</button><button className="button button-primary button-small" disabled={actionId === order.id} onClick={() => void submitForReview(order)}>提交审核</button></>}
					  {order.status === "SUBMITTED" && !hasBusinessReview(order) && isCustomerManager && <button className="button button-secondary button-small" disabled={actionId === order.id} onClick={() => setReviewTarget({ order, reviewer: "CUSTOMER_MANAGER" })}><Check aria-hidden="true" />查看订单并复核</button>}
					  {order.status === "SUBMITTED" && !hasBusinessReview(order) && isGeneralManager && <button className="button button-secondary button-small" disabled={actionId === order.id} onClick={() => setReviewTarget({ order, reviewer: "GENERAL_MANAGER" })}><Check aria-hidden="true" />查看订单并复核</button>}
					  {(order.status === "SUBMITTED" && hasBusinessReview(order) || (order.status === "RELEASED" && order.defaultProcessReleasedAt != null && order.engineeringConfirmedAt == null)) && isEngineer && (
                        <button
                          className="button button-secondary button-small"
                          disabled={actionId === order.id}
								onClick={() => { setEngineeringTemplateId(""); setProductProcessTemplateId(""); setUseDefaultProcessCard(false); setEngineeringOrder(order); }}
                        >
							<Check aria-hidden="true" />工程确认
                        </button>
                      )}
					  {order.status === "SUBMITTED" && isSupervisor && (
						<button
						  className="button button-secondary button-small"
						  disabled={actionId === order.id}
						  onClick={() => remindEngineering(order.id)}
						>
						  <BellRing aria-hidden="true" />催工程确认
						</button>
					  )}
					  {order.status === "SUBMITTED" && hasBusinessReview(order) && order.engineeringConfirmedAt == null && isEngineer && <button className="button button-secondary button-small" disabled={actionId === order.id} onClick={() => setEngineeringReturnOrder(order)}>打回前台</button>}
					  {order.status === "SUBMITTED" && isSupervisor && hasBusinessReview(order) && order.engineeringConfirmedAt == null && (
						<button className="button button-primary button-small" disabled={actionId === order.id} onClick={() => void releaseWithDefaultProcess(order)}><Factory aria-hidden="true" />按默认工艺投产</button>
					  )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="order-mobile-list" aria-label="订单卡片列表">
            {orders.map((order) => (
              <article key={order.id} className={`order-mobile-card ${orderRowTone(order, completedOrderIds) ?? ""}`}>
                <header>
                  <div>
                    <StatusBadge value={order.routeType} />
                    <strong>{order.orderNo}</strong>
                    <small>{order.customerName} · {order.customerCode}</small>
                  </div>
                  <StatusBadge value={order.priority} />
                </header>
                <div className="order-mobile-products">
					{order.lines.map((line) => <span key={line.id}>{line.productName} · {line.productMaterial ?? products.find((product) => product.id === line.productId)?.material ?? "材质未登记"} · {formatQuantity(line.orderedQuantity)} {line.unit}</span>)}
                </div>
                <footer>
                  <div><StatusBadge value={order.status} /><span>{orderToneLabel(order, completedOrderIds)}</span><small>交付：{formatDate(order.requestedDeliveryDate)}</small></div>
                  <div className="order-mobile-actions">
					<div className="order-document-actions">
                    {isFrontDesk && <Link className="text-link" to={`/trace?orderId=${order.id}`}>生产进度</Link>}
                    {order.orderDrawingUrl && <a className="text-link" href={order.orderDrawingUrl} target="_blank" rel="noreferrer">订单图纸</a>}
					{order.contractAttachmentUrl && <a className="text-link" href={order.contractAttachmentUrl} target="_blank" rel="noreferrer">合同附件</a>}
					{order.status === "RELEASED" && <Link className="text-link" to={`/trace?orderId=${order.id}`}>追溯</Link>}
					</div>
					{order.status === "DRAFT" && isFrontDesk && <><button className="button button-secondary button-small" disabled={actionId === order.id} onClick={() => openDraftEditor(order)}>编辑草稿</button><button className="button button-primary button-small" disabled={actionId === order.id} onClick={() => void submitForReview(order)}>提交审核</button></>}
					{order.status === "SUBMITTED" && !hasBusinessReview(order) && isGeneralManager && <button className="button button-secondary button-small" disabled={actionId === order.id} onClick={() => setReviewTarget({ order, reviewer: "GENERAL_MANAGER" })}><Check aria-hidden="true" />订单复核</button>}
					{((order.status === "SUBMITTED" && hasBusinessReview(order)) || (order.status === "RELEASED" && order.defaultProcessReleasedAt != null && order.engineeringConfirmedAt == null)) && isEngineer && <button className="button button-secondary button-small" disabled={actionId === order.id} onClick={() => { setEngineeringTemplateId(""); setProductProcessTemplateId(""); setUseDefaultProcessCard(false); setEngineeringOrder(order); }}><Check aria-hidden="true" />工程确认</button>}
					{order.status === "SUBMITTED" && hasBusinessReview(order) && order.engineeringConfirmedAt == null && isEngineer && <button className="button button-secondary button-small" disabled={actionId === order.id} onClick={() => setEngineeringReturnOrder(order)}>打回前台</button>}
					{order.status === "SUBMITTED" && isSupervisor && <button className="button button-secondary button-small" disabled={actionId === order.id} onClick={() => remindEngineering(order.id)}><BellRing aria-hidden="true" />催工程确认</button>}
					{order.status === "SUBMITTED" && isSupervisor && hasBusinessReview(order) && order.engineeringConfirmedAt == null && <button className="button button-primary button-small" disabled={actionId === order.id} onClick={() => void releaseWithDefaultProcess(order)}><Factory aria-hidden="true" />按默认工艺投产</button>}
                  </div>
                </footer>
              </article>
            ))}
          </div>
          </>
        )}
      </section>

      {showCreate && (
        <Modal
          title={editingOrder ? `编辑草稿订单 - ${editingOrder.orderNo}` : `新建${routeNames[routeType]}订单`}
          description={editingOrder ? "草稿可自由修改客户、产品、订单材质、数量、交期、价格与附件；保存后仍为草稿，确认无误后再单独提交审核。" : "订单先保存为草稿；前台确认信息无误后提交总经理审核，审核通过并完成工程确认后才可放行生产。"}
          width="large"
          onClose={() => { if (!pending) { setShowCreate(false); setEditingOrder(null); } }}
        >
          {error != null && <ErrorNotice error={error} />}
          <form onSubmit={createOrder}>
			<div className="order-intake-grid">
			  <Field label="生产线" required>
				<select value={routeType} onChange={(event) => selectRoute(event.target.value as RouteType)}>
				  {routes.map((route) => <option key={route.value} value={route.value}>{route.label}</option>)}
				</select>
			  </Field>
              <Field label="订单号">
                <input value={editingOrder?.orderNo ?? "保存后自动生成"} readOnly aria-label="订单号由系统自动生成" />
              </Field>
              <Field label="选择或检索客户" required>
				<div className="field-inline-control"><input value={customerSearch} placeholder="输入客户编码、名称或联系人后选择" required onFocus={() => { setPickerQuery(customerSearch); setShowCustomerPicker(true); }} onChange={(event) => { setCustomerSearch(event.target.value); setSelectedCustomerId(""); setPickerQuery(event.target.value); setShowCustomerPicker(true); }} /><button className="icon-button" type="button" aria-label="检索客户" title="检索客户" onClick={() => { setPickerQuery(customerSearch); setShowCustomerPicker(true); }}><Search aria-hidden="true" /></button><button className="icon-button" type="button" aria-label="新建客户" title="新建客户" onClick={() => setShowCreateCustomer(true)}><Plus aria-hidden="true" /></button></div>
				{selectedCustomer && <small className="customer-history-products">该客户历史产品：{customerHistoryProducts.length ? customerHistoryProducts.slice(0, 4).map((product) => `${product.name}${product.specification ? ` (${product.specification})` : ""}`).join("、") : "暂无"}{customerHistoryProducts.length > 4 ? " 等" : ""}</small>}
              </Field>
              <Field label="优先级" required>
                <select name="priority" defaultValue={editingOrder?.priority ?? "NORMAL"}>
                  <option value="SAMPLE">样品单</option>
                  <option value="NORMAL">普通</option>
                  <option value="URGENT">急单</option>
                </select>
              </Field>
              <Field label="要求交付日期">
                <input name="requestedDeliveryDate" type="date" defaultValue={editingOrder?.requestedDeliveryDate ?? ""} />
              </Field>
				<div className="order-intake-wide"><Field label="备注"><textarea name="remark" maxLength={500} rows={2} defaultValue={editingOrder?.remark ?? ""} placeholder="交期、包装、质量或生产协调要求；主管派工时可见" /></Field></div>
				<div className="order-intake-wide"><Field label="订单图纸 / 附件" hint="图片或 PDF，最大 20MB；上传后受控保存"><input type="file" accept="image/jpeg,image/png,image/webp,.pdf" disabled={pending} onChange={(event) => { const file = event.target.files?.[0]; if (!file) return; if (file.size > 20 * 1024 * 1024) { setError(new Error("图纸附件请控制在 20MB 以内")); event.currentTarget.value = ""; return; } void uploadOrderDrawing(file); }} />{orderDrawingUrl && <small className="muted">已上传附件</small>}</Field></div>
				<div className="order-intake-wide"><Field label="合同附件" hint="可选；图片或 PDF，最大 20MB"><input type="file" accept="image/jpeg,image/png,image/webp,.pdf" disabled={pending} onChange={(event) => { const file = event.target.files?.[0]; if (!file) return; if (file.size > 20 * 1024 * 1024) { setError(new Error("合同附件请控制在 20MB 以内")); event.currentTarget.value = ""; return; } void uploadContractAttachment(file); }} />{contractAttachmentUrl && <small className="muted">已上传合同附件</small>}</Field></div>
            </div>

            <div className="line-editor">
              <header>
                <div><h3>订单行</h3><p>每个订单行生成独立工单与生产批次</p></div>
                <button
                  className="button button-secondary button-small"
                  type="button"
                  onClick={() =>
                    setLines((current) => [
                      ...current,
                      newDraftLine()
                    ])
                  }
                >
                  <Plus aria-hidden="true" />添加行
                </button>
              </header>
              {lines.map((line, index) => (
                <div className="line-editor-row" key={line.key}>
                  <span className="line-number">{index + 1}</span>
						<Field label="选择或检索产品" required>
							<div className="field-inline-control"><input value={line.productSearch} placeholder="输入产品编码、名称、规格或材质后选择" required onFocus={() => { setPickerQuery(line.productSearch); setProductPickerLineKey(line.key); }} onChange={(event) => { const productSearch = event.target.value; updateLine(line.key, { productSearch, productId: "", moldAssetId: "", moldSearch: "" }); setPickerQuery(productSearch); setProductPickerLineKey(line.key); }} /><button className="icon-button" type="button" aria-label="检索产品" title="检索产品" onClick={() => { setPickerQuery(""); setProductPickerLineKey(line.key); }}><Search aria-hidden="true" /></button><button className="icon-button" type="button" aria-label="新建产品" title="新建产品" onClick={() => setShowCreateProduct(true)}><Plus aria-hidden="true" /></button></div>
						</Field>
						<Field label="订单材质" required hint="默认带入产品材质；若与所选产品不同，保存时自动建立同产品的独立材质版本，不覆盖原产品。"><input required maxLength={160} value={line.productMaterial} placeholder="例如 CF8、304、316L" onChange={(event) => updateLine(line.key, { productMaterial: event.target.value })} /></Field>
						<div className="order-model-preview" aria-label="所选产品模型图">{routeProducts.find((product) => product.id === line.productId)?.modelImageUrl ? <button type="button" className="model-image-button" title="查看产品模型图" onClick={() => { const product = routeProducts.find((item) => item.id === line.productId)!; setPreviewImage({ url: product.modelImageUrl!, label: `${product.code} · ${product.name}` }); }}><img src={routeProducts.find((product) => product.id === line.productId)?.modelImageUrl ?? ""} alt="点击放大查看所选产品模型图" /></button> : <span>无图</span>}</div>
                  <Field label="数量" required>
                    <input
                      type="number"
							min="1"
							step="1"
							inputMode="numeric"
                      required
                      value={line.quantity}
                      onChange={(event) =>
								updateLine(line.key, { quantity: Math.max(1, Math.trunc(Number(event.target.value) || 1)) })
                      }
                    />
                  </Field>
				  <Field label="单位" required>
                    <input
                      required
                      maxLength={16}
                      value={line.unit}
                      onChange={(event) => updateLine(line.key, { unit: event.target.value })}
                    />
				  </Field>
				  <Field label="成交单价" required hint="仅用于订单商务复核，不进入生产作业单">
					<input type="number" min="0.0001" step="0.0001" inputMode="decimal" required value={line.salesUnitPrice || ""} placeholder="例如 28.50" onChange={(event) => updateLine(line.key, { salesUnitPrice: Number(event.target.value) || 0 })} />
				  </Field>
				  <Field label="计价单位" required>
					<select value={line.salesPriceUnit} onChange={(event) => updateLine(line.key, { salesPriceUnit: event.target.value as SalesPriceUnit })}><option value="TON">每吨</option><option value="KG">每公斤</option><option value="PCS">每个</option><option value="SET">每套</option><option value="EA">每只</option></select>
				  </Field>
				  {routeType !== "SAND_OUTSOURCE" && <div className="line-mold-plan">
						<Field label="模具状态" required>
							<select value={line.moldStatus} onChange={(event) => updateLine(line.key, { moldStatus: event.target.value as DraftMoldStatus, moldAssetId: "", moldSearch: "" })}>
								<option value="IN_STOCK">在库，立即选定</option>
								<option value="CUSTOM_MOLD_TO_RECEIVE">不在库，定制后入库</option>
								<option value="CUSTOMER_DELIVERY_PENDING">不在库，待客户送模入库</option>
							</select>
						</Field>
						{line.moldStatus === "IN_STOCK" && line.productId && selectedCustomer && (() => {
							const customerMatchingMolds = customerProductMoldsForLine(line);
							const productMatchingMolds = productMoldsForLine(line);
							const matchingMolds = customerMatchingMolds.length ? customerMatchingMolds : productMatchingMolds;
							const routeBoundProductCount = products.filter((product) => product.routeType === routeType && customerProductMolds.some((relation) => relation.customerId === selectedCustomer.id && relation.productId === product.id)).length;
							const matchMessage = customerMatchingMolds.length
								? `该客户 + 该产品已绑定 ${customerMatchingMolds.length} 套在库模具`
								: productMatchingMolds.length
									? `当前客户暂无专用关系，已按产品匹配 ${productMatchingMolds.length} 套在库模具`
									: routeBoundProductCount === 0
										? `该客户暂无${routeNames[routeType]}产品关系；所选产品也未绑定可用模具`
										: "该客户未绑定此产品，所选产品也未绑定可用模具";
							return <div className="mold-match-panel">
								<div className="mold-match-heading"><strong>智能匹配</strong><span>{matchMessage}</span></div>
								{matchingMolds.length > 0 && <div className="mold-match-options">
									{matchingMolds.map((mold) => <button key={mold.id} className={`mold-match-choice ${line.moldAssetId === mold.id ? "is-selected" : ""}`} type="button" onClick={() => updateLine(line.key, { moldAssetId: mold.id, moldSearch: `${mold.assetCode} · ${mold.assetName}` })}>
										<div><strong>{mold.assetCode} · {mold.assetName}</strong><span>{mold.ownershipType === "CUSTOMER_OWNED" ? `客户寄存 · ${mold.ownerName ?? "未登记"}` : "企业自有"} · {moldLocationLabel(mold)}</span></div>
										<small>{line.moldAssetId === mold.id ? "已选定" : matchingMolds.length === 1 ? "已自动带入" : "点击选用"}</small>
									</button>)}
								</div>}
							</div>;
						})()}
						{line.moldStatus === "IN_STOCK" ? <Field label="在库模具" required hint={selectedCustomer ? "优先展示该客户历史使用模具；可直接搜索模具编号。" : "请先选择客户后检索模具。"}>
							<div className="field-inline-control"><input value={line.moldSearch} placeholder="点击检索并选择在库模具" readOnly required /><button className="icon-button" type="button" aria-label="检索模具" title="检索模具" onClick={() => { setPickerQuery(""); setMoldPickerLineKey(line.key); }}><Search aria-hidden="true" /></button>{canReceiveMold && <button className="icon-button" type="button" aria-label="快速入库新模具" title="快速入库新模具" disabled={!line.productId} onClick={() => { setQuickMoldOwnership(selectedCustomer ? "CUSTOMER_OWNED" : "COMPANY_OWNED"); setQuickMoldLocation(""); setQuickMoldLocationMode("AUTO"); setQuickMoldReceiptLineKey(line.key); }}><Plus aria-hidden="true" /></button>}</div>
						</Field> : <Field label="待入库说明" hint={line.moldStatus === "CUSTOMER_DELIVERY_PENDING" ? "模具仓管入库时必须按此订单产品绑定，且登记为该客户寄存。" : "模具仓管入库时必须按此订单产品绑定。"}>
							<input value={line.moldPlanNote} maxLength={500} placeholder={line.moldStatus === "CUSTOMER_DELIVERY_PENDING" ? "例如：客户预计 8 月 2 日送达" : "例如：模具定制单号 / 预计入库日期"} onChange={(event) => updateLine(line.key, { moldPlanNote: event.target.value })} />
						</Field>}
				  </div>}
                  <button
                    className="icon-button danger"
                    type="button"
                    aria-label={`删除第 ${index + 1} 行`}
                    disabled={lines.length === 1}
                    onClick={() =>
                      setLines((current) => current.filter((item) => item.key !== line.key))
                    }
                  >
                    <Trash2 aria-hidden="true" />
                  </button>
                </div>
              ))}
            </div>
            <SubmitActions
              pending={pending}
              submitLabel={editingOrder ? "保存草稿修改" : "保存草稿"}
              onCancel={() => { setShowCreate(false); setEditingOrder(null); }}
            />
          </form>
        </Modal>
      )}
		{showCustomerPicker && <Modal title="检索客户" description="支持客户编码和名称检索；选择客户后会优先推荐其历史使用模具。" width="large" onClose={() => setShowCustomerPicker(false)}>
			<Field label="客户检索"><input value={pickerQuery} autoFocus placeholder="输入客户编码或名称" onChange={(event) => setPickerQuery(event.target.value)} /></Field>
			<div className="selection-picker-list">
				{pickerCustomers.map((customer) => {
					const historyCount = new Set(orders.filter((order) => order.customerId === customer.id).flatMap((order) => order.lines.map((line) => line.productId))).size;
					return <button key={customer.id} type="button" onClick={() => chooseCustomer(customer.id, `${customer.code} · ${customer.name}`)}><div><strong>{customer.name}</strong><span>{customer.code}</span></div><small>历史产品 {historyCount} 个</small></button>;
				})}
				{pickerCustomers.length === 0 && <EmptyState title="未找到客户" description="可调整检索条件，或直接新建客户。" />}
			</div>
			<div className="picker-footer"><Link className="text-link" to="/customers">进入客户管理</Link><button className="button button-secondary" type="button" onClick={() => { setShowCustomerPicker(false); setShowCreateCustomer(true); }}><Plus aria-hidden="true" />新建客户</button></div>
		</Modal>}
		{productPickerLine && <Modal title="检索产品" description={`${selectedCustomer ? "该客户历史产品已置顶；" : ""}仅显示${routeNames[routeType]}产品；可按编码、名称、规格和材质组合检索。`} width="large" onClose={() => setProductPickerLineKey(null)}>
			<Field label="产品检索"><input value={pickerQuery} autoFocus placeholder="例如：阀体、DN25、CF8 或产品编码" onChange={(event) => setPickerQuery(event.target.value)} /></Field>
			<div className="selection-picker-list product-picker-list">
				{pickerProducts.map((product) => <button key={product.id} type="button" onClick={() => { updateLine(productPickerLine.key, { productId: product.id, productSearch: `${product.code} · ${product.name}`, productMaterial: product.material ?? "" }); setProductPickerLineKey(null); }}><span className="product-picker-thumb">{product.modelImageUrl ? <img src={product.modelImageUrl} alt={`${product.name} 模型图`} /> : <span>无图</span>}</span><div><strong>{product.code} · {product.name}</strong><span>{product.specification ?? "未填规格"} · {product.material ?? "未填材质"}</span></div><small>{customerHistoryProductIdSet.has(product.id) ? "客户历史" : product.active ? "可用" : "历史停用"}</small></button>)}
				{pickerProducts.length === 0 && <EmptyState title="未找到产品" description="请检查产品名称、规格、材质或产品编码。" />}
			</div>
			<div className="picker-footer"><Link className="text-link" to="/master-data">进入产品管理</Link><button className="button button-secondary" type="button" onClick={() => { setProductPickerLineKey(null); setShowCreateProduct(true); }}><Plus aria-hidden="true" />新建产品</button></div>
		</Modal>}
		{moldPickerLine && <Modal title="检索在库模具" description="优先展示当前客户与产品已绑定的模具；其余在库模具仍可手动选择。" width="large" onClose={() => setMoldPickerLineKey(null)}>
			<Field label="模具检索"><input value={pickerQuery} autoFocus placeholder="模具编码、名称、库位、客户名称或权属" onChange={(event) => setPickerQuery(event.target.value)} /></Field>
			<div className="selection-picker-list mold-picker-list">
				{pickerMolds.map((mold) => <button key={mold.id} type="button" onClick={() => { updateLine(moldPickerLine.key, { moldAssetId: mold.id, moldSearch: `${mold.assetCode} · ${mold.assetName}` }); setMoldPickerLineKey(null); }}><div><strong>{mold.assetCode} · {mold.assetName}</strong><span>{mold.ownershipType === "CUSTOMER_OWNED" ? `客户寄存 · ${mold.ownerName ?? "未登记"}` : "企业自有"} · {moldLocationLabel(mold)}</span></div><small>{customerProductMolds.some((relation) => relation.customerId === selectedCustomerId && relation.productId === moldPickerLine.productId && relation.moldAssetId === mold.id) ? "客户产品匹配" : productMolds.some((relation) => relation.productId === moldPickerLine.productId && relation.moldAssetId === mold.id) ? "产品匹配" : customerHistoryMoldIds.has(mold.id) ? "历史推荐" : "在库可用"}</small></button>)}
				{pickerMolds.length === 0 && <EmptyState title="未找到可用在库模具" description="请调整检索条件，或在模具状态中选择待定制入库/待客户送模。" />}
			</div>
			<div className="picker-footer"><Link className="text-link" to="/order-molds">进入订单模具选定</Link></div>
		</Modal>}
		{showCreateCustomer && <Modal title="新建客户" description="前台可直接建档；系统自动生成客户编码，保存后可立即用于当前订单。敏感资料变更仍按权限申请。" onClose={() => !pending && setShowCreateCustomer(false)}>
			<form onSubmit={createCustomer}><div className="form-grid">
				<Field label="客户名称" required><input name="name" required maxLength={160} autoFocus /></Field>
				<Field label="联系人"><input name="contactName" maxLength={100} /></Field>
				<Field label="联系电话"><input name="contactPhone" maxLength={40} /></Field>
				<Field label="销售人员"><input name="salesOwner" maxLength={100} placeholder="负责销售或客户经理" /></Field>
			</div><SubmitActions pending={pending} submitLabel="提交建档申请" onCancel={() => setShowCreateCustomer(false)} /></form>
		</Modal>}
		{showCreateProduct && <Modal title="新建产品" description="产品编码自动生成；规格和材质在订单创建前必须明确。" onClose={() => !pending && setShowCreateProduct(false)}>
			<form onSubmit={createProduct}><div className="form-grid">
				<Field label="产品名称" required><input name="name" required maxLength={160} autoFocus /></Field>
				<Field label="工艺路线" required><input value={routeNames[routeType]} readOnly /></Field>
				<Field label="路线版本" required><input name="routeVersion" required maxLength={32} defaultValue="V1" /></Field>
				<Field label="产品规格" required><input name="specification" required maxLength={500} /></Field>
				<Field label="材质" required><input name="material" required maxLength={160} /></Field>
				<Field label="产品模型图"><input type="file" accept="image/jpeg,image/png,image/webp" disabled={pending} onChange={(event) => { const file = event.target.files?.[0]; if (file) void uploadProductModel(file); }} />{productModelImageUrl && <small className="muted">已上传模型图</small>}</Field>
			</div><SubmitActions pending={pending} submitLabel="保存产品" onCancel={() => setShowCreateProduct(false)} /></form>
		</Modal>}
		{engineeringOrder && (
			<Modal
				title={`工程确认 - ${engineeringOrder.orderNo}`}
					description="订单中的每个产品必须分别确认工艺卡。已发布的同产品工艺默认带入，可逐项修改；放行后每个产品行生成独立工单与批次。"
				onClose={() => !pending && setEngineeringOrder(null)}
			>
					<form onSubmit={confirmEngineering}>
						<div className="form-toolbar"><button className="button button-secondary button-small" type="button" onClick={() => setUseDefaultProcessCard(true)}>带入本产线默认工艺</button>{useDefaultProcessCard && <span className="muted">已带入默认草案，请按各产品复核。</span>}</div>
						{engineeringOrder.lines.map((line, index) => {
							const template = productProcessTemplates.find((item) => item.productId === line.productId && item.status === "PUBLISHED");
							const operationParameters = useDefaultProcessCard
								? defaultProcessCards[engineeringOrder.routeType].operations
								: parseOperationParameters(line.engineeringOperationParameters ?? template?.operationParameters);
							const summary = useDefaultProcessCard ? defaultProcessCards[engineeringOrder.routeType].summary : line.engineeringParameters ?? template?.engineeringParameters ?? "";
							return <fieldset className="engineering-line-card" key={line.id}><legend>产品 {index + 1}：{line.productCode} · {line.productName} · {formatQuantity(line.orderedQuantity)} {line.unit}</legend><div className="form-grid">
								<Field label="默认来源"><input value={template ? `${template.templateNo} · ${template.version}（已发布）` : "未找到已发布产品工艺卡"} readOnly /></Field>
								<Field label="工艺卡版本"><input name={`processCardVersion-${line.id}`} maxLength={64} defaultValue={line.processCardVersion ?? template?.version ?? ""} placeholder="留空自动生成" /></Field>
								<Field label="详细参数与工艺要求" required><textarea name={`engineeringParameters-${line.id}`} required maxLength={2000} rows={5} defaultValue={summary} /></Field>
								<Field label="射蜡参数"><textarea name={`waxInjectionParameters-${line.id}`} rows={3} defaultValue={operationParameters.WAX_INJECTION ?? ""} /></Field>
								<Field label="修蜡参数"><textarea name={`waxRepairParameters-${line.id}`} rows={3} defaultValue={operationParameters.WAX_REPAIR ?? ""} /></Field>
								<Field label="组树参数"><textarea name={`treeAssemblyParameters-${line.id}`} rows={3} defaultValue={operationParameters.TREE_ASSEMBLY ?? ""} /></Field>
								<Field label="制壳参数"><textarea name={`shellParameters-${line.id}`} rows={3} defaultValue={operationParameters.SHELL_BUILDING ?? operationParameters.MANUAL_SHELL_BUILDING ?? ""} /></Field>
								<Field label="脱蜡参数"><textarea name={`dewaxParameters-${line.id}`} rows={3} defaultValue={operationParameters.DEWAX ?? ""} /></Field>
								<Field label="浇筑参数"><textarea name={`pouringParameters-${line.id}`} rows={3} defaultValue={operationParameters.POURING ?? ""} /></Field>
								<Field label="脱壳与分割参数"><textarea name={`knockoutParameters-${line.id}`} rows={3} defaultValue={operationParameters.KNOCKOUT_CUTTING ?? ""} /></Field>
								<Field label="后处理参数"><textarea name={`finishingParameters-${line.id}`} rows={3} defaultValue={operationParameters.OPTIONAL_FINISHING ?? ""} /></Field>
							</div></fieldset>;
						})}
						<SubmitActions pending={pending} submitLabel="确认工程工艺" onCancel={() => setEngineeringOrder(null)} />
				</form>
			</Modal>
		)}
		{engineeringReturnOrder && <Modal title={`工程打回 - ${engineeringReturnOrder.orderNo}`} description="仅退回前台补充或更正订单资料；客户经理/总经理已完成的本次商务复核保留，工程确认后会直接放行。" width="small" onClose={() => !pending && setEngineeringReturnOrder(null)}><form onSubmit={returnForEngineering}><Field label="打回原因" required hint="前台会收到通知并在订单列表中看到该原因"><textarea name="reason" rows={4} maxLength={500} required autoFocus /></Field><SubmitActions pending={pending} submitLabel="确认打回前台" onCancel={() => setEngineeringReturnOrder(null)} /></form></Modal>}
		{reviewTarget && <Modal title={`订单复核 - ${reviewTarget.order.orderNo}`} description="请先核对客户、交期、图纸、合同、产品规格材质、数量与成交单价，再执行本次复核。" width="large" onClose={() => !pending && setReviewTarget(null)}><div className="order-review-detail"><dl className="document-preview-fields"><div><dt>客户</dt><dd>{reviewTarget.order.customerName} · {reviewTarget.order.customerCode}</dd></div><div><dt>生产线 / 优先级</dt><dd>{routeNames[reviewTarget.order.routeType]} · {reviewTarget.order.priority}</dd></div><div><dt>要求交付日期</dt><dd>{reviewTarget.order.requestedDeliveryDate ? formatDate(reviewTarget.order.requestedDeliveryDate) : "未填写"}</dd></div><div><dt>订单备注</dt><dd>{reviewTarget.order.remark || "无"}</dd></div><div><dt>订单图纸</dt><dd>{reviewTarget.order.orderDrawingUrl ? <a className="text-link" href={reviewTarget.order.orderDrawingUrl} target="_blank" rel="noreferrer">查看附件</a> : "未上传"}</dd></div><div><dt>合同附件</dt><dd>{reviewTarget.order.contractAttachmentUrl ? <a className="text-link" href={reviewTarget.order.contractAttachmentUrl} target="_blank" rel="noreferrer">查看合同</a> : "未上传"}</dd></div></dl><section className="section-block"><header className="section-heading"><div><h2>订单产品明细</h2><p>规格、材质、成交单价与模型图均在本次商务复核中核对；复核通过后不进入生产执行页面。</p></div></header><div className="table-scroll"><table><thead><tr><th>产品</th><th>规格</th><th>材质</th><th>数量</th><th>成交单价</th><th>模型图</th></tr></thead><tbody>{reviewTarget.order.lines.map((line) => { const product = state.data?.products.find((item) => item.id === line.productId); return <tr key={line.id}><td><strong>{line.productCode}</strong><small>{line.productName}</small></td><td>{product?.specification ?? "未维护"}</td><td>{line.productMaterial ?? product?.material ?? "未维护"}</td><td>{formatQuantity(line.orderedQuantity)} {line.unit}</td><td>{line.salesUnitPrice == null ? "未录入" : `${formatQuantity(line.salesUnitPrice)} / ${salesPriceUnitLabel(line.salesPriceUnit)}`}</td><td>{line.modelImageUrl ? <button className="text-link" type="button" onClick={() => setPreviewImage({ url: line.modelImageUrl!, label: line.productName })}>查看</button> : "未上传"}</td></tr>; })}</tbody></table></div></section></div><div className="modal-actions"><button className="button button-secondary" type="button" onClick={() => setReviewTarget(null)}>暂不复核</button>{reviewTarget.reviewer === "CUSTOMER_MANAGER" && <button className="button button-secondary" type="button" disabled={pending} onClick={() => { setCustomerManagerReturnOrder(reviewTarget.order); setReviewTarget(null); }}>打回前台</button>}{reviewTarget.reviewer === "GENERAL_MANAGER" && <button className="button button-secondary" type="button" disabled={pending} onClick={() => { setGeneralManagerReturnOrder(reviewTarget.order); setReviewTarget(null); }}>打回前台</button>}<button className="button button-primary" type="button" disabled={actionId === reviewTarget.order.id} onClick={() => void reviewOrder(reviewTarget.order.id, reviewTarget.reviewer)}><Check aria-hidden="true" />确认复核通过</button></div></Modal>}
		{customerManagerReturnOrder && <Modal title={`客户经理打回 - ${customerManagerReturnOrder.orderNo}`} description="订单会保留为草稿，前台将在订单列表与消息中心收到打回原因。" width="small" onClose={() => !pending && setCustomerManagerReturnOrder(null)}><form onSubmit={returnByCustomerManager}><Field label="打回原因" required hint="请说明需补充或更正的客户、交期、产品、数量、价格或附件信息。"><textarea name="reason" rows={4} maxLength={500} required autoFocus /></Field><SubmitActions pending={pending} submitLabel="确认打回前台" onCancel={() => setCustomerManagerReturnOrder(null)} /></form></Modal>}
		{generalManagerReturnOrder && <Modal title={`总经理打回 - ${generalManagerReturnOrder.orderNo}`} description="订单将恢复为可编辑草稿；前台修改后必须重新提交审核，本轮审核和工程确认不会沿用。" width="small" onClose={() => !pending && setGeneralManagerReturnOrder(null)}><form onSubmit={returnByGeneralManager}><Field label="打回原因" required hint="请说明需要更正的客户、产品材质、数量、价格、交期或附件。"><textarea name="reason" rows={4} maxLength={500} required autoFocus /></Field><SubmitActions pending={pending} submitLabel="确认打回前台" onCancel={() => setGeneralManagerReturnOrder(null)} /></form></Modal>}
    {quickMoldReceiptLine && <Modal title="快速入库新模具" description={`入库后自动选定给当前订单行的 ${quickMoldReceiptProduct?.code ?? "产品"} ${quickMoldReceiptProduct?.name ?? ""}；自动分配仅使用空库位，手动选择会在保存时校验库位冲突。模具图可由仓管后续补录。`} width="small" onClose={() => !pending && setQuickMoldReceiptLineKey(null)}>
      <form onSubmit={receiveQuickMold}><div className="form-grid">
        <Field label="订单产品"><input value={`${quickMoldReceiptProduct?.code ?? "未选择"} · ${quickMoldReceiptProduct?.name ?? ""}`} readOnly /></Field>
        <Field label="模具编码" hint="留空自动生成"><input name="assetCode" maxLength={64} autoFocus /></Field>
        <Field label="模具名称" required><input name="assetName" required maxLength={160} defaultValue={quickMoldReceiptProduct?.name ? `${quickMoldReceiptProduct.name}模具` : ""} /></Field>
        <Field label="库位分配" required><select value={quickMoldLocationMode} onChange={(event) => setQuickMoldLocationMode(event.target.value as "AUTO" | "MANUAL")}><option value="AUTO">自动分配空库位</option><option value="MANUAL">手动选择库位</option></select></Field>
        {quickMoldLocationMode === "AUTO" ? <Field label="系统推荐库位"><input value={moldLocations.find((location) => location.occupiedCount === 0)?.locationCode ?? "暂无空库位"} readOnly /></Field> : <Field label="入库库位" required hint="仅可选择空库位；保存时会再次校验。"><select name="locationCode" required value={quickMoldLocation} onChange={(event) => setQuickMoldLocation(event.target.value)}><option value="" disabled>选择空库位</option>{moldLocations.filter((location) => location.occupiedCount === 0).map((location) => <option key={location.locationCode} value={location.locationCode}>{location.locationCode} · 空</option>)}</select></Field>}
        <Field label="模具权属" required><select name="ownershipType" value={quickMoldOwnership} onChange={(event) => setQuickMoldOwnership(event.target.value as "COMPANY_OWNED" | "CUSTOMER_OWNED")}><option value="COMPANY_OWNED">企业自有</option><option value="CUSTOMER_OWNED">客户寄存</option></select></Field>
        {quickMoldOwnership === "CUSTOMER_OWNED" && <Field label="寄存客户" required><input name="ownerName" required maxLength={160} defaultValue={selectedCustomer?.name ?? ""} /></Field>}
      </div><SubmitActions pending={pending} submitLabel="入库并选定" onCancel={() => setQuickMoldReceiptLineKey(null)} /></form>
    </Modal>}
			{previewImage && <Modal title="产品模型图" description={previewImage.label} width="large" onClose={() => setPreviewImage(null)}><div className="image-preview-dialog"><img src={previewImage.url} alt={`${previewImage.label} 产品模型图`} /></div></Modal>}
    </>
  );
}

function completedOrders(tasks: Task[]) {
	const byOrder = new Map<string, Task[]>();
	tasks.forEach((task) => byOrder.set(task.orderId, [...(byOrder.get(task.orderId) ?? []), task]));
	return new Set([...byOrder.entries()].filter(([, orderTasks]) => orderTasks.length > 0 && orderTasks.every((task) => task.status === "COMPLETED")).map(([orderId]) => orderId));
}

function salesPriceUnitLabel(unit: SalesPriceUnit | null) {
	return ({ TON: "吨", KG: "公斤", PCS: "个", SET: "套", EA: "只" } as Record<SalesPriceUnit, string>)[unit ?? "PCS"];
}

function parseOperationParameters(value: string | null | undefined): Record<string, string> {
	if (!value) return {};
	try {
		const parsed = JSON.parse(value) as Record<string, unknown>;
		return Object.fromEntries(Object.entries(parsed).filter(([, parameter]) => typeof parameter === "string")) as Record<string, string>;
	}
	catch { return {}; }
}

function parseOperationImages(value: string | null | undefined): Record<string, string> {
	if (!value) return {};
	try {
		const images = (JSON.parse(value) as Record<string, unknown>)._operationImages;
		if (images == null || typeof images !== "object") return {};
		return Object.fromEntries(Object.entries(images as Record<string, unknown>).filter(([, image]) => typeof image === "string")) as Record<string, string>;
	} catch { return {}; }
}

function orderRowTone(order: Order, completedOrderIds: Set<string>) {
	if (order.priority === "URGENT") return "order-row-urgent";
	if (completedOrderIds.has(order.id)) return "order-row-completed";
	if (order.status === "RELEASED") return "order-row-released";
	return undefined;
}

function orderToneLabel(order: Order, completedOrderIds: Set<string>) {
	if (order.priority === "URGENT") return "加急";
	if (completedOrderIds.has(order.id)) return "已完成";
	if (order.status === "RELEASED") return "已投产";
	if (order.status === "DRAFT") return "草稿待提交";
	if (order.status === "SUBMITTED") return hasBusinessReview(order) ? "待工程确认" : "待总经理审核";
	return "已生成工单";
}

function reviewGateLabel(order: Order) {
	if (order.status === "RELEASED") return "复核已通过";
	if (order.status === "DRAFT") return "前台可修改；确认无误后提交审核";
  const business = hasBusinessReview(order)
    ? `${order.customerManagerReviewedAt ? "客户经理" : "总经理"}已复核`
    : "待客户经理或总经理复核";
  const engineering = order.engineeringConfirmedAt ? "工程已确认" : "待工程确认";
  return `${business} / ${engineering}`;
}

function hasBusinessReview(order: Order) {
  return Boolean(order.customerManagerReviewedAt || order.generalManagerReviewedAt);
}
