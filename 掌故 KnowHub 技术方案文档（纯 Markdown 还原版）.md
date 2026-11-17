<!--
  本文件由《掌故 KnowHub 技术方案文档》的浏览器 HTML 快照机器还原而成，仅修复格式，不改动技术内容。
  还原来源：掌故 KnowHub 技术方案文档.md（151,702 字节 HTML 快照，正文约 13,719 字符）
  还原日期：2026-11-17
  使用提示：正文中「表格」占位、编号列表层级、段落合并处需人工校对一遍，再作为文档基线入库。
-->

# 掌故 KnowHub 技术方案文档
> **项目名称：** 掌故  
> **英文代号：** KnowHub  
> **项目释义：** 掌典册之故，答众人之询，引原文为据  
> **版本：** V1.0  
> **日期：** 2026-11-17  
> **定位：** 多端统一 Agent 智能体 + 可调教 RAG 知识库 + 业务流程编排嵌入平台  
> **终端覆盖：** Android / 鸿蒙 HarmonyOS /iOS/ Web H5 / 微信小程序 / Vue 管理后台  
> **技术底座：** Kotlin Multiplatform (KMP) 端侧内核 + Python FastAPI AI 中台，支持【云端联网 Agent】+【Android / 鸿蒙端侧离线 Agent 双模式】  

文档包含：需求总览、可行性校验、技术选型（前沿 + 稳定取舍）、完整架构、模块详细设计、数据流、接口契约、部署方案、风险清单、落地路线、质量保障。

---

## 目录

1. 项目概述 & 核心需求

2. 技术可行性全面校验（重点章节）

3. 技术栈选型 ｜ 稳定底座 + 前沿能力取舍

4. 整体系统架构

5. 中台（Python FastAPI）模块详细设计

6. KMP 端侧共享内核详细设计

7. 各端适配方案（Android / 鸿蒙 /iOS/ Web H5 / 小程序 / Admin 后台）

8. 数据模型 & FlowTemplate 流程编排 Schema

9. 统一 API 契约（SSE/WS 流式）

10. 部署架构 & 容器化方案

11. 风险清单、边界约束、稳定性保障策略

12. 分阶段落地里程碑

13. 扩展性规划（前沿能力预留）

---

# 1. 项目概述 & 核心需求

### 核心目标
构建一套企业内部 Agent 应用，区别于普通 RAG 问答系统：

1. ✅ 知识库支持可视化调教：文档增删、切片策略、正负样本、权重、强制溯源开关

2. ✅ ReAct Agent：区分知识库问答、业务流程执行、调教指令三类意图

3. ✅ 业务流程低代码嵌入：Agent 自动收集表单参数、调用内部业务工具、分支流转

4. ✅ 全终端覆盖：Android、鸿蒙、iOS、H5、微信小程序、Web 管理后台

5. ✅ 双模运行：
* 联网模式：算力在 Python 中台，所有端统一调用

* 离线模式：Android / 鸿蒙本地独立运行（本地向量库 + 本地 LLM），断网可用，联网自动双向同步

6. ✅ 审计、权限、会话管理、问答统计大盘

### 非需求边界（避免无限膨胀）

* 不自研基础大模型；基于开源 LLM（Qwen、DeepSeek）、Embedding、Rerank 模型

* 移动端不做复杂拖拽编排；完整版编排仅在 Vue Admin 后台，移动端仅轻量化调教

* Web、小程序不支持端侧离线 LLM，受浏览器 / 小程序沙箱、内存限制

---

# 2. 技术可行性全面校验（逐条校验）
核心结论：整体方案可行，不存在技术黑盒；部分前沿能力有明确硬件 / 版本约束，已经在方案中做降级兜底下面分模块校验可行性、已知限制、稳定性兜底方案

## 2.1 Python FastAPI AI 中台（RAG、Agent、文档解析、模型网关）

* FastAPI + Uvicorn：成熟生产级，异步原生支持 SSE，稳定性验证充足 ✅

* Milvus 向量库：生产级向量引擎，支持百万级文档 Chunk，支持分片、索引；企业知识库量级完全 hold 住 ✅

* 文档解析：unstructured + PyPDF + python-docx + PaddleOCR，PDF/Word/PPT/ 图片 OCR 均有成熟工业方案；复杂扫描件 OCR 性能可控 ✅

* 批量向量化任务：Celery+Redis 异步队列，避免上传阻塞，失败重试、任务状态可追溯 ✅

* LLM 网关：vLLM 做推理服务，OpenAI 兼容接口，支持流式输出、动态批处理，吞吐量高；可对接公有 API（DeepSeek/Qwen）兜底 ✅

* ReAct Agent 中台调度：Python 生态 LangGraph 适合编排思考、检索、工具调用，社区成熟，可控制幻觉、限制思考轮次 ✅

风险点：大批量文档解析、OCR 属于 CPU 密集任务；兜底：扩容 worker，大文档分片解析，超时熔断

## 2.2 KMP Kotlin Multiplatform 端侧共享内核

* 共享层：commonMain 纯 Kotlin 业务逻辑、数据模型、Agent 状态机、SQLDelight 本地数据库 ✅

* 平台源集：androidMain / iosMain / hmMain / jsMain，KMP 官方稳定支持 ✅

* SQLDelight：跨端本地数据库，Android=SQLite、Web=IndexedDB、小程序 = Storage 适配、鸿蒙 = ArkDB 桥接，持久化方案成熟 ✅

* 端侧向量检索：SQLite-VSS（SQLite 向量扩展），轻量、无额外服务依赖，适合手机端小知识库（几千 Chunk 以内） ✅

限制：SQLite-VSS 不适合十万级以上向量；兜底：大知识库强制走云端 Milvus，本地仅缓存少量高频文档

## 2.3 端侧离线推理：llama.cpp 移动端（Android + 鸿蒙）

* llama.cpp：C/C++ 量化推理，支持 Q4_K_M 量化，ARM 架构完美适配 Android、鸿蒙 ✅

* KMP 绑定：Kotlin JNI 封装 llama.cpp，common 层抽象 LlmProvider，平台层注入 native 实现 ✅

* 可行性边界：
* 高端手机：7B 量化模型可跑（速度一般，适合内部查询）

* 中低端手机：7B 内存压力大，自动降级到云端模式

兜底策略：设备能力检测，自动开关离线模式；不强制低端机跑本地模型

## 2.4 鸿蒙 HarmonyOS 适配

* 架构方案：ArkUI 原生 UI + hmMain KMP 桥接层，复用 common Agent/RAG 逻辑 ✅

* llama.cpp 可编译鸿蒙 OHOS 架构 so，鸿蒙 NDK 支持 ✅

* 限制：鸿蒙沙箱严格，文件、存储权限管控强；内存管控比 Android 更严格

兜底：离线能力做设备白名单，低端鸿蒙设备禁用本地 LLM

## 2.5 Web H5（Compose for Web）

* Compose for Web 稳定版，KMP jsMain 编译，共享 common 逻辑 ✅

* 限制：浏览器环境无法跑 llama.cpp，向量检索必须走中台；IndexedDB 容量有限

兜底：H5 只做联网轻量化问答，不开放离线能力

## 2.6 微信小程序（薄壳优先方案）

* 优先薄壳方案：小程序仅视图，全部请求 Python 中台 WebSocket 接口，不引入 KMP JS 核心 ✅

* 可行性：wx 小程序原生支持 WebSocket，消息结构和 SSE 对齐，开发成本低、坑最少

* 备选：KMP JS 编译到小程序 utils，适合团队强 Kotlin 偏好；但 JS 环境内存、异步限制多，作为二期可选，不作为 MVP ✅

稳定性策略：MVP 坚决薄壳，规避 KMP JS 在小程序的兼容坑

## 2.7 Vue 管理后台（流程编排、大盘）

* Vue3 + Element Plus / NaiveUI，拖拽式流程编排、表单设计器、报表，成熟开源组件生态 ✅

* 只调用中台 HTTP 接口，和 KMP 完全解耦，迭代独立，适合复杂管理功能 ✅

## 2.8 流式通信（SSE / WebSocket）

* SSE：Android、鸿蒙、Web H5 原生支持，实现简单、稳定 ✅

* WebSocket：专门给小程序，消息协议和 SSE 保持一致，上层业务代码几乎不用改 ✅

校验：两套通道，同一套事件结构体，中台统一封装

## 2.9 同步机制（端侧 ↔ 中台双向增量同步）

* 版本号机制：每个配置 / 文档携带版本号，SyncManager 对比版本，增量拉取 / 推送 ✅

* 冲突策略：中台权威优先；移动端轻量化调教提交中台后，中台做合并、审计日志记录 ✅

✅ 整体可行性结论：无不可行项；所有前沿能力都配套稳定降级兜底方案，MVP 全部使用成熟底座，前沿特性（端侧 Agent、本地 LLM）作为二期增强，不阻塞上线

---

# 3. 技术栈选型 ｜ 稳定底座 + 前沿能力取舍
原则：MVP 优先稳定成熟；前沿技术作为可选增强，开关化控制，可关闭而不影响主流程

## 3.1 Python AI 中台（稳定底座）
| 组件 | 选型 | 说明 |
| --- | --- | --- |
| Web 框架 | FastAPI + Uvicorn | 异步、SSE 友好，生产广泛使用 |
| 任务队列 | Celery + Redis | 异步文档解析、OCR、向量化 |
| 向量数据库 | Milvus（Standalone） | 企业知识库主向量库，稳定可扩 |
| 关系库 | PostgreSQL | 用户、权限、会话、模板、审计日志 |
| 对象存储 | MinIO | 原始文档、图片存储 |
| 缓存 | Redis | 会话缓存、限流、任务状态 |
| LLM 推理网关 | vLLM | OpenAI 兼容，批量、流式，高性能 |
| Agent 编排 | LangGraph | Python 侧 ReAct Agent，控制思考轮次、工具调用 |
| Embedding | BGE-large-zh | 中文检索标杆，稳定 |
| Rerank | BGE-reranker | 召回后重排，提升 RAG 精度 |
| 文档解析 | unstructured、PyPDF、python-docx | 通用文档解析 |
| OCR | PaddleOCR | 扫描件、图片文字识别 |

## 3.2 KMP 端侧共享内核（Kotlin）
| 组件 | 选型 | 说明 |
| --- | --- | --- |
| KMP | Kotlin 2.0+ | 稳定跨端，支持 Android/iOS/JS/HM |
| UI（Android） | Jetpack Compose | 原生 Android UI |
| UI（iOS） | SwiftUI | iOS 原生 |
| UI（鸿蒙） | ArkUI(ArkTS) | 鸿蒙原生 |
| UI（Web H5） | Compose for Web | 轻量化 H5 |
| 本地数据库 | SQLDelight | 跨端本地持久化 |
| 端侧向量 | SQLite-VSS | 轻量本地向量检索（Android / 鸿蒙离线） |
| 本地 LLM 推理 | llama.cpp（JNI 绑定） | 端侧量化模型推理 |
| 网络 | Ktor | KMP 跨端 HTTP/WebSocket 客户端 |

## 3.3 前端管理 & 小程序
| 端 | 选型 | 说明 |
| --- | --- | --- |
| Web 管理后台 | Vue3 + NaiveUI + X6（拖拽编排） | X6 是阿里开源图编排引擎，适合流程拖拽 |
| 微信小程序 | 原生小程序（wxml/wxss/js）薄壳 | MVP 方案 |

## 3.4 前沿能力清单（可开关，不阻塞 MVP）
下面这些是前沿特性，方案预留架构，二期迭代，不放在 MVP：

1. 端侧本地 ReAct Agent（Android / 鸿蒙离线 Agent）

2. 端侧 SQLite-VSS 本地向量检索

3. llama.cpp 端侧量化大模型推理

4. 多模态文档（图文混合理解）

5. 知识库自动评估、自动生成负样本（RAG 自动评测）

6. Agent 记忆分层：会话短期记忆 + 长期知识库记忆

7. 鸿蒙超级终端流转（对话任务跨设备流转）

8. MCP 工具协议标准化（后续替换自研 ToolRegistry）

## 3.5 稳定底线（MVP 只使用这些）
Python 中台 RAG、SSE 流式、KMP 共享状态、Android Compose、Vue 后台、小程序薄壳联网问答；不开启任何端侧离线模型。

---

# 4. 整体系统架构

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                          接入网关 Nginx / FastAPI Gateway
│  鉴权、SSO、限流、CORS、终端标记(X-Terminal-Type)、SSE/WS路由分发
└───────────────────────────────────────────────────────────┬─────────────────────────┘
                                                            │
┌───────────────────────────────────────────────────────────┼─────────────────────────┐
│                     Python FastAPI AI中台集群
│  ├─ AuthService：用户、角色、权限、SSO登录
│  ├─ Document&RAGService：文档上传、异步解析、OCR、切片、Embedding、Milvus召回、Rerank
│  ├─ AgentService（LangGraph）：ReAct意图识别、检索、工具调用、流程执行
│  ├─ LLM Gateway(vLLM)：统一大模型接入、流式输出、负载
│  ├─ FlowService：FlowTemplate管理、变量解析、分支逻辑校验
│  ├─ TuneService：正负样本、RAG参数、Prompt模板管理
│  ├─ SyncService：增量同步版本管理、变更合并
│  └─ Audit&StatsService：操作审计、问答统计、未命中报表
└───────────────────────────────────────────────────────────┬─────────────────────────┘
├─ 存储层
│  PostgreSQL｜Milvus｜MinIO｜Redis
└───────────────────────────────────────────────────────────┬─────────────────────────┘
                                                            │
┌───────────────────────────────────────────────────────────┼─────────────────────────┐
│                   KMP Shared Common 共享内核（Kotlin）
│ AgentOrchestrator｜ReActThought｜IntentClassifier
│ KnowBase RAGPipeline｜FlowEmbedder｜ToolRegistry
│ PromptTemplateStore｜SyncManager｜SQLDelight本地库
│ PlatformAdapter【平台抽象接口】
└───────────────────────────────────┬─────────────────────────┘
                                    │
┌───────────┬──────────┬────────┬────────────┬──────────────┬────────────────────┐
│ Android   │ 鸿蒙HM    │ iOS    │ Web H5     │ 微信小程序   │ Vue Admin后台     │
│ Compose   │ ArkUI    │SwiftUI │Compose Web │ 薄壳（纯API）│ 完整版编排大盘    │
│ ✅离线LLM │ ✅离线LLM │可选离线│ ❌离线      │ ❌离线       │ 仅调用中台API     │
└───────────┴──────────┴────────┴────────────┴──────────────┴────────────────────┘
```

## 双模运行逻辑

1. **联网模式（默认）**所有端 → 网关 → Python 中台 Agent+RAG；KMP 端侧只做状态、消息渲染、本地缓存。

2. **离线模式（Android / 鸿蒙，手动 / 自动切换）**用户断网 → KMP 本地 AgentOrchestrator + SQLite-VSS 本地向量 + llama.cpp 本地 LLM； 联网触发 SyncManager：本地新增文档、调教样本、变更配置，增量推送到中台，中台合并入库。

---

# 5. Python 中台模块详细设计

## 5.1 RAG 服务流程

1. 文件上传 → MinIO 存储，生成 docId

2. 投递 Celery 异步任务：文档解析 → 清洗 → 分块（支持多种 chunk 策略：固定 token、标题分割）

3. OCR（图片 / 扫描件）→ 文本提取

4. BGE embedding → 向量写入 Milvus，记录 chunk 元数据（文档名、页码、索引）

5. 查询阶段：query 向量化 → Milvus 召回 TopK → BGE rerank 重排 → 过滤 → 拼接上下文给 LLM

## 5.2 Agent 服务（LangGraph ReAct）
状态流转：State{query, intent, observation, tool_list, source_list, flow_context, answer}

1. 意图分类：知识库查询 / 业务流程 / 调教指令 / 闲聊

2. 知识库查询：调用 RAG 服务，拿到 source 片段，写入 observation

3. 业务流程：加载 FlowTemplate，按节点依次收集参数、调用 Tool、分支判断

4. 工具调用：统一走 /tool/invoke，中台鉴权、转发下游 OA / 工单接口，返回结果写入 observation

5. 最终生成流式回答，附带溯源 source、flow 步骤

安全限制：最大思考轮次限制，防止无限循环；工具调用白名单，禁止任意接口调用

## 5.3 Flow 流程模板服务
FlowTemplate JSON Schema 见下文，中台负责：模板 CRUD、校验、下发给端侧缓存、执行流程节点。
节点类型：参数收集节点 / 工具调用节点 / 条件分支节点 / 知识库引用节点 / 消息输出节点

## 5.4 同步服务
每个资源（prompt、flow 模板、rag 配置、文档）携带 version；/sync/pull：传入本地版本列表，中台返回变更项；/sync/push：端侧提交本地调教变更，中台做权限校验、冲突合并、写入审计日志。

## 5.5 审计日志
所有修改类操作：文档新增、prompt 修改、流程编辑、正负样本标注、工具调用，全部记录：操作人、终端类型、时间、变更内容、IP。

---

# 6. KMP 端侧共享内核详细设计

## 6.1 包结构

```
shared/
├─ src/commonMain/kotlin/com/knowhub/zhanggu/
│  ├─ agent/
│  │  ├─ AgentOrchestrator.kt
│  │  ├─ ReActThought.kt
│  │  ├─ IntentClassifier.kt
│  │  └─ prompt/PromptTemplateStore.kt
│  ├─ knowbase/
│  │  ├─ RAGPipeline.kt
│  │  ├─ ChunkStrategy.kt
│  │  ├─ VectorRepository.kt
│  │  └─ TuneSample.kt
│  ├─ flow/
│  │  ├─ FlowEmbedder.kt
│  │  ├─ ToolRegistry.kt
│  │  ├─ FlowTemplate.kt
│  │  └─ FlowNode.kt
│  ├─ llm/
│  │  ├─ LlmProvider.kt
│  │  ├─ RemoteLlmProvider.kt
│  │  └─ LocalLlamaCppProvider.kt
│  ├─ sync/SyncManager.kt
│  ├─ network/ Ktor客户端
│  └─ db/ SQLDelight表定义
├─ src/androidMain/
│  └─ platform/ PlatformAdapter实现、llama.cpp JNI、OCR、文件
├─ src/hmMain/
│  └─ platform/ 鸿蒙NDK桥接、ArkUI能力封装
├─ src/iosMain/
└─ src/jsMain/
```

## 6.2 PlatformAdapter 抽象接口（common）

```
interface PlatformAdapter {
    suspend fun pickFile(): DocumentFile?
    suspend fun ocrImage(imageBytes: ByteArray): String
    suspend fun showNotification(title: String, content: String)
    fun getLocalLlmProvider(): LlmProvider? // Android/鸿蒙返回实例，其余null
    fun supportOfflineMode(): Boolean
}
```

## 6.3 本地离线 RAG（仅 Android / 鸿蒙）

* VectorRepository 双实现：RemoteVectorRepo（调用中台）、LocalVectorRepo（SQLite-VSS）

* 离线模式下，RAGPipeline 自动切本地向量库；联网切远端

## 6.4 端侧轻量化调教
端侧只开放：上传文档、标注正负样本、修改本地 Prompt；复杂流程编排、批量文档导入全部在 Vue 后台

---

# 7. 各端适配方案
| 终端 | UI 层 | 联网能力 | 离线 LLM | 调教权限 | 备注 |
| --- | --- | --- | --- | --- | --- |
| Android | Jetpack Compose | ✅ | ✅ | 轻量化调教 | 主端，完整离线闭环 |
| 鸿蒙 | ArkUI ArkTS | ✅ | ✅ | 轻量化调教 | NDK 编译 llama.cpp，沙箱管控严格 |
| iOS | SwiftUI | ✅ | ⚠️ 可选 | 轻量化调教 | 苹果对本地推理内存管控严格，默认关闭离线 |
| Web H5 | Compose for Web | ✅ | ❌ | 轻量化调教 | IndexedDB 缓存，不支持本地大模型 |
| 微信小程序 | 原生小程序 JS（薄壳） | ✅ | ❌ | 轻量化调教 | MVP 方案，WebSocket 流式；二期可选 KMP JS |
| Vue 管理后台 | Vue3 + X6 | ✅ | ❌ | 完整版编排 | 流程拖拽、批量文档、报表、全局配置 |

---

# 8. FlowTemplate JSON Schema（全端通用）

```
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "type": "object",
  "title": "FlowTemplate",
  "properties": {
    "flowId": {"type":"string"},
    "name": {"type":"string"},
    "triggerKeywords": {"type":"array","items":{"type":"string"}},
    "description": {"type":"string"},
    "version": {"type":"integer"},
    "nodes": {
      "type":"array",
      "items": {
        "type":"object",
        "properties": {
          "nodeId": {"type":"string"},
          "nodeType": {"type":"string","enum":["collect_param","tool_invoke","branch","source_ref","message"]},
          "config": {"type":"object"},
          "nextNodeId": {"type":["string","null"]},
          "branchMap": {"type":"object"}
        },
        "required":["nodeId","nodeType"]
      }
    },
    "variables": {"type":"object"}
  },
  "required":["flowId","name","triggerKeywords","nodes","version"]
}
```

* collect_param：收集参数（向用户提问）

* tool_invoke：调用注册工具

* branch：条件分支

* source_ref：引用知识库条款

* message：输出提示文本

---

# 9. 统一 API 契约
统一前缀：/api/v1 公共 Header

```
Authorization: Bearer {token}
X-Terminal-Type: android / hm / ios / web / mini / admin
X-Session-Id: {sessionId}
```

## 流式接口

1. SSE（Android / 鸿蒙 / Web H5）：GET /api/v1/chat/stream

2. WebSocket（小程序）：ws://host/api/v1/chat/ws 流事件结构（二者完全一致）

```
{
  "type": "text | source | flow_step | done | error",
  "content": "文本片段",
  "source": [{"docId":"xxx","docName":"xxx.pdf","page":2,"chunkIndex":12}],
  "flow": {"flowId":"flow_001","stepKey":"collect_date","desc":"收集加班日期"}
}
```
其余接口清单和上一版一致：auth、document、rag、agent、flow、tool、sync、audit、stats。

---

# 10. 部署架构 & Docker Compose
整体容器化，分两组：

1. AI 中台容器组：FastAPI、Celery Worker、Redis、PostgreSQL、Milvus、MinIO、vLLM

2. 前端静态资源：Vue Admin、H5 静态包，Nginx 托管

开发环境一键 docker-compose；生产环境建议 K8s，vLLM 独立 GPU 节点，Milvus 独立集群。

```
# 核心服务清单
services:
  nginx:
  postgres:
  redis:
  milvus:
  minio:
  fastapi:
  celery-worker:
  vllm:
```

---

# 11. 风险清单 & 稳定性保障策略
| 风险 | 影响 | 应对方案 |
| --- | --- | --- |
| 端侧 llama.cpp 内存溢出（低端手机） | App 闪退 | 设备能力检测，自动禁用离线 LLM，强制切云端；离线模式开关 |
| 小程序 SSE 不稳定 | 对话断流 | 小程序单独 WebSocket 通道，消息结构对齐 |
| 大批量文档 OCR / 解析耗时久 | 接口超时 | 异步 Celery 任务，状态轮询，失败重试，任务优先级 |
| RAG 幻觉 | 回答不可靠 | 强制溯源开关开启，回答必须绑定 chunk；Rerank 过滤；正负样本持续调教 |
| 多端同步冲突 | 配置覆盖 | 中台权威，版本号控制，冲突标记，审计日志留存 |
| Milvus 索引失效、召回差 | 问答质量下滑 | 定时重建索引、监控召回指标、向量库告警 |
| 权限越权 | 管理员能力泄露 | 所有调教、修改接口后端鉴权，前端只做视图隐藏 |
| KMP JS 小程序兼容性坑 | 上线阻塞 | MVP 薄壳方案，二期再评估 KMP JS |
| 模型推理过载 | 流式卡顿 | vLLM 动态批处理、限流、队列、降级到公有 LLM |

## 稳定性保障手段

* 全链路日志、异常告警

* 接口限流、熔断、超时控制

* 灰度发布，先开放 Android、后台，再开放小程序、鸿蒙

* 功能开关：RAG 开关、Agent 流程开关、离线模式开关，线上可一键关闭高危特性

* 单元测试：common 核心逻辑（Agent、FlowEmbedder）Kotlin 单元测试；Python 服务 Pytest

---

# 12. 分阶段落地里程碑

### 阶段 1｜MVP（稳定优先，2~3 周）
目标：联网闭环，无任何端侧离线模型

* Python FastAPI 中台基础 RAG、SSE 对话、文档上传异步解析

* Vue 后台：文档管理、Prompt 配置、流程编排 X6

* Android Compose：对话、溯源、轻量化文档上传

* 微信小程序薄壳：问答、溯源

* 权限、会话、审计日志

### 阶段 2｜ 知识库调教 + 多端补齐（2~3 周）

* 正负样本调教、RAG 参数配置

* Web H5（Compose for Web）

* iOS 接入 KMP

* 同步 SyncManager，端侧配置、模板增量同步

### 阶段 3｜ 鸿蒙接入 + 端侧离线 Agent（3~4 周）

* 鸿蒙 ArkUI + KMP hmMain 桥接

* Android / 鸿蒙 SQLite-VSS 本地向量、llama.cpp 本地推理

* 离线模式、联网双向同步

* 端侧完整 ReAct Agent

### 阶段 4｜ 优化与前沿能力（持续迭代）

* RAG 自动评测、自动负样本生成

* MCP 工具协议标准化

* 鸿蒙超级终端流转

* 多模态文档理解

---

# 13. 扩展性预留

1. 工具层抽象：ToolRegistry，未来可对接 MCP 协议，替换自研工具调用

2. LLM 网关抽象：支持切换不同大模型服务商、本地模型

3. 知识库多租户隔离：预留租户字段，后续支持多部门独立知识库

4. 插件化 Agent：支持新增自定义 Agent 技能，不用改核心调度代码

---

# 文档收尾备注
本方案已经完成可行性校验，所有前沿特性均设计开关降级策略，MVP 全部使用工业稳定成熟技术栈，不存在依赖未成熟科研项目的情况；KMP 负责端侧统一交互内核，Python 负责重型 AI 中台，分工清晰，避免重复造轮子。

