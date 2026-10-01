export type GuideLink = {
  label: string;
  path: string;
  permission: string | string[];
};

export type RoleGuide = {
  code: string;
  name: string;
  mission: string;
  startOfDay: string;
  steps: string[];
  handoff: string;
  links: GuideLink[];
};

export type ProductionLineGuide = {
  code: "MID_TEMP_WAX" | "LOW_TEMP_WAX" | "SAND_OUTSOURCE";
  name: string;
  summary: string;
  steps: Array<{ operation: string; owner: string; action: string; tool: string }>;
};

export const productionLineGuides: ProductionLineGuide[] = [
  {
    code: "MID_TEMP_WAX",
    name: "中温蜡线",
    summary: "蜡间按件或按树计件，制壳可走自动线或手工线；后段依炉次、工时和后处理路线执行。",
    steps: [
      { operation: "投产与领模", owner: "中温蜡间主管、模具仓管", action: "确认投产余量和批次，领用已绑定模具并打印派工单、流转卡。", tool: "工单批次、生产派工、模具领用、文档打印" },
      { operation: "射蜡", owner: "射蜡工、蜡间主管", action: "扫码核对模具，按本工序参数报合格、废品和可选照片；主管可代录纸质单。", tool: "我的工作台、扫码、电子工单、报工总账" },
      { operation: "修蜡", owner: "修蜡工、蜡间主管", action: "核对前序实收，处理数量差异后继续报工；大批小件可直接交接组树。", tool: "电子工单、交接核对、交接异常" },
      { operation: "组树", owner: "组树工、蜡间主管", action: "填写每树件数和树数，上传组树图，按工艺路线交接到自动或手工制壳。", tool: "树数报工、工艺卡、周转车流转" },
      { operation: "制壳", owner: "自动线主管或手工制壳工", action: "记录制壳层次、干燥、照片和下一动作；可等待下一层或申请流转。", tool: "制壳报备、扫码、制壳车间大屏" },
      { operation: "熔炼后段", owner: "后续工艺主管、脱蜡/浇筑/脱壳/分割工", action: "脱蜡、浇筑、脱壳、分割依序报工；炉次记录温度、材质批次和装炉量。", tool: "电子工单、炉次台账、交接异常" },
      { operation: "后处理与交付", owner: "后处理主管、成品仓管、物流", action: "选择本厂、外送或直入成品仓；终检合格后入库、备货、发运和签收。", tool: "后处理选择、质量管理、成品物流交付" }
    ]
  },
  {
    code: "LOW_TEMP_WAX",
    name: "低温蜡线",
    summary: "路线与中温蜡后段一致，但蜡间仅记录进度、不产生射蜡修蜡组树计件工资，制壳固定为人工制壳。",
    steps: [
      { operation: "投产与领模", owner: "低温蜡间主管、模具仓管", action: "确认批次后领用本订单产品已绑定的可用模具，生成首道派工单和流转卡。", tool: "工单批次、生产派工、模具领用" },
      { operation: "射蜡、修蜡、组树", owner: "低温蜡工、低温蜡间主管", action: "按本工序参数报进度、数量、废品和交接；主管可按纸质单统一回填。", tool: "我的工作台、电子工单、报工总账" },
      { operation: "人工制壳", owner: "低温蜡制壳主管、手工制壳工", action: "逐层记录制壳、干燥、照片和继续制壳或流转的决定。", tool: "制壳报备、扫码、制壳车间大屏" },
      { operation: "脱蜡至分割", owner: "后续工艺主管、操作工", action: "按工时记录脱蜡、浇筑、脱壳和分割，浇筑关联炉号与材质批次。", tool: "电子工单、炉次台账、交接异常" },
      { operation: "后处理与交付", owner: "后处理主管、成品仓管、物流", action: "按成品状态选择后处理、外送或直入成品仓，再完成质检和交付。", tool: "后处理选择、质量管理、成品物流交付" }
    ]
  },
  {
    code: "SAND_OUTSOURCE",
    name: "砂型外协线",
    summary: "当前按外协业务管理，不进入蜡模和制壳工位；来料检验合格后直接进入成品库存与交付，并保留未来自建产线的扩展边界。",
    steps: [
      { operation: "订单与工程确认", owner: "前台、客户经理或总经理、工艺工程师", action: "建立砂型外协订单，完成一次订单复核，确认图纸、材质、验收要求和外协工艺。", tool: "客户订单、工艺卡库、附件、消息中心" },
      { operation: "外协发出", owner: "外协协调员、原材料仓管", action: "建立外协任务，登记供应商、数量、承诺日期、发出物料和运输信息。", tool: "外协管理、仓库流水、订单追溯" },
      { operation: "外协进度", owner: "外协协调员、生产总管", action: "更新供应商生产中、在途、催交和异常；急单仅调整尚未执行的外协计划。", tool: "外协管理、生产概览、消息中心" },
      { operation: "来料检验与交付", owner: "质量人员、成品仓管、物流", action: "回厂后按来料检验处理合格、返工或退货，合格件入成品库后发运签收。", tool: "质量管理、成品物流交付、订单追溯" }
    ]
  }
];

export const operatingRuleSummaries = [
  { title: "一单多产品", detail: "订单行是生产最小业务单元。每个产品行独立形成工单、生产批次和任务链；模具、材质、工艺版本和完成数量都不会串行。" },
  { title: "顺序与拆批", detail: "只有前序已有可交接合格数时，下游任务才可开工。部分报工会生成可流转数量和剩余任务；同产品的未开工下游批次可按规则合并。" },
  { title: "异常不堵产", detail: "实收差异、缺失、损坏或照片说明会进入交接异常待办并通知主管。原始数量不覆盖，允许在受控记录下继续生产。" },
  { title: "电子与纸质并行", detail: "员工可用小程序自行报工；未使用系统时，主管按纸质派工单回填数量、工时、废品和交接结果，记录补录人和来源。" },
  { title: "扫码只做明确动作", detail: "扫码记录操作者、工位和时间。大屏只读定位；个人工作台中仍须明确选择查看、接收、开工或交接，扫码不会自动改变状态。" },
  { title: "仓库分账", detail: "模具以领用、归还、维修和权属台账追溯；原材料以收发和批次台账追溯；成品以合格批次、备货、物流和签收台账追溯。" }
] as const;

export const productionStages = [
  {
    number: "01",
    title: "资料与工艺准备",
    owner: "工艺工程师",
    action: "维护客户、产品、工艺路线，发布对应工序 SOP。",
    done: "产品可选、路线正确、SOP 为已发布版本。",
    next: "生产主管创建订单"
  },
  {
    number: "02",
    title: "创建客户订单",
    owner: "生产主管",
    action: "录入客户、交期、产品、数量和优先级，保存为草稿。",
    done: "订单行和交期核对无误，状态为草稿。",
    next: "审批订单"
  },
  {
    number: "03",
    title: "审批并放行",
    owner: "生产主管",
    action: "先审批订单，再执行放行。",
    done: "状态为已放行，系统自动生成工单、批次和对应路线的工序任务。",
    next: "排产与派工"
  },
  {
    number: "04",
    title: "备料与派工",
    owner: "生产主管 / 仓库管理员",
    action: "核对工单批次，为各工序分配人员；按现场要求完成备料出库。",
    done: "任务已分派到具体工号，现场物料可用。",
    next: "操作员开工"
  },
  {
    number: "05",
    title: "顺序生产报工",
    owner: "一线操作员",
    action: "阅读安全提示后开工，按 SOP 作业并记录质量结果，填报合格数和报废数。",
    done: "本工序已完成且数量守恒，下一工序才可开始。",
    next: "逐道流转至成品清点"
  },
  {
    number: "06",
    title: "检验与处置",
    owner: "质量检验员",
    action: "登记检验数量、合格数和不合格数；不合格品选择返工、报废或让步。",
    done: "检验数量平衡，不合格数量全部完成处置。",
    next: "终检合格品转成品入库"
  },
  {
    number: "07",
    title: "成品登记入库",
    owner: "仓库管理员",
    action: "选择已完成且终检合格的最终任务，登记成品批次和库位。",
    done: "入库数不超过未登记的终检合格数，形成可发库存。",
    next: "交付专员创建发货单"
  },
  {
    number: "08",
    title: "备货发运签收",
    owner: "交付专员 / 仓库管理员",
    action: "创建发货单、确认备货、登记承运商与运单号、确认客户签收。",
    done: "发货单依次完成草稿、已备货、运输中、已签收。",
    next: "关闭交付并追溯复盘"
  },
  {
    number: "09",
    title: "追溯与经营复核",
    owner: "主管 / 质量 / 财务",
    action: "按订单查看全时间线，复核产量、质量、计件和交付结果。",
    done: "业务事实完整可查，异常有责任人和处置结论。",
    next: "进入下一订单周期"
  }
] as const;

export const productionOperations = [
  "射蜡",
  "修蜡",
  "组树",
  "制壳",
  "脱蜡",
  "浇筑",
  "脱壳与分割",
  "半成品清点",
  "后处理",
  "成品清点"
] as const;

export const roleGuides: RoleGuide[] = [
  {
    code: "GLOBAL_SCHEDULER",
    name: "全局生产调度",
    mission: "统筹中温蜡、低温蜡和砂型外协三条线的未开工队列，处理样品单、急单和产能冲突。",
    startOfDay: "先查看三线队列、交期风险、被占用资源和未处理通知，再处理需要调整的未开工工单。",
    steps: [
      "在三线排产中按样品、急单、正常单和交期查看每道工序的待开工任务。",
      "只调整未开工任务的顺序；已开工、已装车、已入炉、干燥中或已独占资源的任务不得被插队中断。",
      "多个急单冲突时，按样品优先、交期、等待时间和主管确认顺序决策，并在通知中说明原因。",
      "发现模具、设备、炉次或人员冲突时，协调车间主管确定下一可用资源，而不直接改变在制品状态。"
    ],
    handoff: "排产结果交给对应车间主管派工；重大交期冲突升级给生产负责人。",
    links: [
      { label: "进入三线排产", path: "/scheduling", permission: "SCHEDULE_MANAGE" },
      { label: "查看生产概览", path: "/dashboard", permission: "DASHBOARD_VIEW" }
    ]
  },
  {
    code: "WORKSHOP_SUPERVISOR",
    name: "车间主管",
    mission: "将已排产工单安全地投产、接收、派工、流转，并保证每一次数量交接可追溯。",
    startOfDay: "查看本车间待接收批次、待派工任务、在制异常和当班可用人员与资源。",
    steps: [
      "核对上游移交的订单、批次、产品、数量和工艺卡片段；差异先登记异常，不覆盖原始数量。",
      "从本工序队列选择已允许开工的任务并派给员工；员工只在小程序查看自己的任务和报工。",
      "按工艺卡确认树件数、制壳路线、层数、干燥时间、炉次容量及质量点；必要时占用对应资源。",
      "接收员工报工后核对合格、废品、返工与剩余数量，完成后将合格数量移交至下游车间。",
      "需要插单时只调整下一个可开工位置，不能把加工中的批次撤下生产线。"
    ],
    handoff: "将可流转数量、车号或炉次和异常说明交给下游主管；最终工序完成后通知质量检验。",
    links: [
      { label: "进入生产派工", path: "/tasks", permission: "TASK_DISPATCH" },
      { label: "进入我的工作台", path: "/workbench", permission: "WORKBENCH_VIEW" },
      { label: "查看工艺卡", path: "/master-data", permission: "PROCESS_CARD_VIEW" }
    ]
  },
  {
    code: "MOLD_KEEPER",
    name: "模具仓管理员",
    mission: "确保模具的权属、状态、领用、归还和保管责任准确，避免错领、串用或客户资产失控。",
    startOfDay: "查看待工程确认、待出库和待归还模具申请，先核对客户寄存模具和已被占用的模具。",
    steps: [
      "登记或核对模具资产：企业自有模具标记为企业自有；客户模具必须标记为客户寄存并填写客户名称。",
      "主管申请后，等待工程确认产品和模具适配；没有现模的申请停留在需定制模具，不能出库。",
      "对已确认申请扫码核对模具编码、权属、产品和仓位，再执行出库；系统同时锁定该模具。",
      "归还时核对实物、状态和数量后解除占用。客户寄存模具还应保留客户交接或返还确认。",
      "客户模具仅用于该客户已授权的产品或订单；维修、改造和对外返还均需记录客户确认。"
    ],
    handoff: "出库后将模具交给对应车间主管并通知任务可投产；归还完成后将状态回写给申请主管。",
    links: [
      { label: "进入模具领用", path: "/molds", permission: ["MOLD_REQUEST", "MOLD_ISSUE"] },
      { label: "进入库存管理", path: "/inventory", permission: "INVENTORY_MANAGE" }
    ]
  },
  {
    code: "CART_OPERATOR",
    name: "周转车操作员",
    mission: "通过车码记录混装、转运、卸车和交接，让每件在制品都能追溯到订单、批次和人员。",
    startOfDay: "检查本人车辆状态、空载容量、待装载批次和待接收车间。",
    steps: [
      "扫码选择固定资产车码，只选择已经投产且当前工序允许装载的订单批次。",
      "逐订单录入本车数量；混装前核对工艺卡的隔离、容量和禁忌规则，可按规定拍照留存。",
      "到达下游车间后由下游主管扫码接收并核对数量，部分卸车也必须保留车载明细。",
      "发现数量、标签、实物或禁忌冲突时停止转运，登记异常并通知双方主管。"
    ],
    handoff: "下游主管完成接收确认后，车载责任结束；未接收的在制品仍保留在车辆台账中。",
    links: [{ label: "进入我的工作台", path: "/workbench", permission: "WORKBENCH_VIEW" }]
  },
  {
    code: "SYSTEM_ADMIN",
    name: "系统管理员",
    mission: "保证账户、权限、资源、接口任务和平台配置可用，不代替业务岗位日常操作。",
    startOfDay: "先看平台运维中的失败接口、未读通知和资源占用，再处理权限申请。",
    steps: [
      "在账户权限中建立角色，并按最小权限原则配置功能权限。",
      "为一个账户分配一个或多个角色；有效权限自动取全部角色的并集。",
      "在平台运维中维护组织成员、设备资源、通知和接口任务。",
      "在审批配置中处理流程申请和配置版本，避免申请人自批。",
      "用订单追溯核对故障恢复后业务事实是否完整。"
    ],
    handoff: "权限或平台问题恢复后，通知对应业务负责人重新执行被阻塞的动作。",
    links: [
      { label: "进入账户权限", path: "/access", permission: "ACCESS_MANAGE" },
      { label: "进入平台运维", path: "/administration", permission: "PLATFORM_ADMIN" },
      { label: "进入审批配置", path: "/platform", permission: "WORKFLOW_MANAGE" }
    ]
  },
  {
    code: "PRODUCTION_MANAGER",
    name: "生产总管",
    mission: "把客户需求转成可执行工单，持续消除未放行、未派工和生产阻塞。",
    startOfDay: "先看生产概览和临期订单，再处理草稿订单、待派工任务和异常批次。",
    steps: [
      "在客户订单中创建订单，核对客户、产品、数量、交期和优先级。",
      "审批订单后再放行；放行会一次性生成工单、批次和对应路线的工序任务。",
      "在工单批次中核对计划数量与状态。",
      "在生产派工中把每道任务分配到具体工号，必要时调整人员。",
      "通过生产概览和订单追溯跟踪在制、报废、终检和交付结果。"
    ],
    handoff: "任务分派完成后交给操作员；最终工序完成后提醒质量检验员执行终检。",
    links: [
      { label: "进入客户订单", path: "/orders", permission: "ORDER_MANAGE" },
      { label: "进入生产派工", path: "/tasks", permission: "TASK_DISPATCH" },
      { label: "查看生产概览", path: "/dashboard", permission: "DASHBOARD_VIEW" }
    ]
  },
  {
    code: "OPERATOR",
    name: "一线操作员",
    mission: "只处理分配给自己的任务，按 SOP 安全、准确地完成开工和报工。",
    startOfDay: "打开我的工作台，优先处理生产中任务，再处理已分派任务。",
    steps: [
      "选择本人任务，核对任务号、批次、工序和计划数量。",
      "阅读安全要求和工前准备提示，按现场规定完成防护用品、设备与现场点检。",
      "点击开始作业；前序任务未完成时系统会阻止开工。",
      "按 SOP 与质量要点作业，如实记录现场结果。",
      "填写合格数与报废数并完成报工；累计数量不得超过计划数量。"
    ],
    handoff: "报工完成即交给下一工序；发现设备、物料或质量异常时停止操作并通知班组长。",
    links: [{ label: "进入我的工作台", path: "/workbench", permission: "WORKBENCH_VIEW" }]
  },
  {
    code: "QUALITY_INSPECTOR",
    name: "质量检验员",
    mission: "确认报工数量的质量结果，并让每一件不合格品都有明确处置。",
    startOfDay: "先处理已完成任务的待检数量和未关闭的不合格处置。",
    steps: [
      "在质量管理中选择任务，核对工序、批次和已报工合格数。",
      "录入检验数、合格数和不合格数，三者必须数量平衡。",
      "为不合格数量登记返工、报废或让步接收，不得遗漏。",
      "最终工序完成后执行终检，成品入库以终检合格数为上限。",
      "通过订单追溯复核质量记录、处置结果和成品批次。"
    ],
    handoff: "终检合格后通知仓库登记成品；返工则交回生产主管重新安排。",
    links: [
      { label: "进入质量管理", path: "/quality", permission: "QUALITY_MANAGE" },
      { label: "查看订单追溯", path: "/trace", permission: "TRACE_VIEW" }
    ]
  },
  {
    code: "WAREHOUSE_CLERK",
    name: "仓库管理员",
    mission: "保证库存流水准确，并把终检合格品转成可发成品批次。",
    startOfDay: "核对待收发库存、待登记成品和待备货发货单。",
    steps: [
      "在库存管理中登记入库、出库或调整，出库后库存不得为负。",
      "按生产安排完成备料，保留物料、仓库和数量流水。",
      "在成品交付中选择已完成且终检合格的最终任务。",
      "登记成品批次、入库数量和成品仓库，数量不得超出可登记上限。",
      "对草稿发货单执行确认备货，核对批次和实物数量。"
    ],
    handoff: "备货完成后交给交付专员登记物流；数量差异先冻结操作并通知主管。",
    links: [
      { label: "进入库存管理", path: "/inventory", permission: "INVENTORY_MANAGE" },
      { label: "进入成品交付", path: "/fulfillment", permission: "FULFILLMENT_MANAGE" }
    ]
  },
  {
    code: "DELIVERY_COORDINATOR",
    name: "交付专员",
    mission: "把可发库存准确交付给客户，并完整记录物流和签收结果。",
    startOfDay: "查看待备货、待发运和运输中的发货单，优先处理交期临近订单。",
    steps: [
      "选择有可发数量的成品批次，填写发货数量、收货人和地址。",
      "创建发货单后协调仓库确认备货。",
      "备货完成后登记承运商和运单号，状态进入运输中。",
      "收到客户回执后确认签收，不提前关闭运输中单据。",
      "在订单追溯中确认发货、物流和签收事件完整。"
    ],
    handoff: "签收完成后把交付结果反馈给生产主管；拒收或破损转质量与主管处理。",
    links: [
      { label: "进入成品交付", path: "/fulfillment", permission: "FULFILLMENT_MANAGE" },
      { label: "查看订单追溯", path: "/trace", permission: "TRACE_VIEW" }
    ]
  },
  {
    code: "PROCESS_ENGINEER",
    name: "工艺工程师",
    mission: "保证产品、路线和现场 SOP 版本一致，变更有发布记录且可追溯。",
    startOfDay: "检查待投产产品的资料完整性、SOP 发布状态和配置变更申请。",
    steps: [
      "在基础资料中维护客户、产品和产品所用工艺路线。",
      "在 SOP 管理中按工序维护安全提示、准备事项、步骤和质量点。",
      "发布新 SOP 版本；现场工作台只读取最新已发布版本。",
      "在审批配置中提交或发布配置版本，重大变更按审批流程执行。",
      "变更后用订单追溯确认新订单使用的工艺事实。"
    ],
    handoff: "资料和 SOP 发布后通知生产主管放行订单；现场反馈问题时建立新版本，不覆盖历史。",
    links: [
      { label: "进入基础资料", path: "/master-data", permission: "MASTERDATA_MANAGE" },
      { label: "进入 SOP 管理", path: "/sops", permission: "SOP_MANAGE" },
      { label: "进入审批配置", path: "/platform", permission: "CONFIG_MANAGE" }
    ]
  },
  {
    code: "FINANCE_REVIEWER",
    name: "财务复核员",
    mission: "依据已确认报工复核计件数量和金额，避免重复、超量和自我审批。",
    startOfDay: "查看待确认计件记录和经营看板中的产量、报废与交付异常。",
    steps: [
      "维护或核对工序计件单价及其生效状态。",
      "按已完成任务和操作员工号建立候选计件记录。",
      "核对计件数量不超过任务合格数量，检查重复记录。",
      "由非创建人确认候选记录，保留确认人和确认时间。",
      "通过生产概览复核产量、质量和交付口径。"
    ],
    handoff: "差异记录退回生产主管核对报工，不直接修改已经确认的生产事实。",
    links: [
      { label: "进入计件管理", path: "/piecework", permission: "PIECEWORK_MANAGE" },
      { label: "查看生产概览", path: "/dashboard", permission: "DASHBOARD_VIEW" }
    ]
  }
];

export const exceptionRules = [
  {
    title: "前序未完成",
    response: "不要绕过顺序。由生产主管检查前序任务状态、报工数量和人员分派。"
  },
  {
    title: "数量不一致",
    response: "暂停报工、检验、入库或发货，按计划、合格、报废、已检、已入库、已发逐级核对。"
  },
  {
    title: "质量不合格",
    response: "质量人员先登记检验，再完成返工、报废或让步处置；未处置数量不能直接入库。"
  },
  {
    title: "库存不足",
    response: "不允许负库存或超可发数量。仓库核对批次流水，主管决定补料、补产或调整交付。"
  },
  {
    title: "设备或安全异常",
    response: "一线人员立即停止作业并保护现场，通知班组长；恢复前不得继续确认 SOP 步骤。"
  },
  {
    title: "权限或接口异常",
    response: "记录业务单号和错误信息，由管理员检查角色权限、接口任务和通知，不重复盲目提交。"
  }
] as const;
