# StockPulse 股市分析即時報價看板

股市即時報價看板 MVP。目前完成「模擬股價 → 事件發布 → WebSocket 即時推播 → 前端顯示」
的完整資料流骨架，尚未串接真實行情來源與指標計算邏輯。

## 目錄

- [技術棧](#技術棧)
- [系統架構](#系統架構)
- [主要資料流](#主要資料流)
- [模組結構](#模組結構)
- [核心類別說明](#核心類別說明)
- [前端（PWA）](#前端pwa)
- [環境設定](#環境設定)
- [部署（Render）](#部署render)
- [已知限制與技術債](#已知限制與技術債)
- [後續規劃](#後續規劃)

## 技術棧

| 類別 | 技術 |
|---|---|
| 框架 | Spring Boot 4.1.0 / Java 21 |
| 即時通訊 | Spring WebSocket（原生 `TextWebSocketHandler`，未使用 STOMP） |
| 排程 | Spring `@Scheduled`（取代真實行情來源前的暫代方案） |
| 事件驅動 | Spring `ApplicationEventPublisher` / `@EventListener`（**應用內事件**，非 Kafka） |
| 關聯式資料庫 | PostgreSQL（Supabase），透過 Spring Data JPA + Hibernate |
| 快取 | Spring Data Redis（Upstash） |
| Java 21 特性 | Record（`Symbol`、`Tick`）、Virtual Thread（推播用） |
| 前端 | 原生 HTML + Vanilla JS，具備最基本 PWA 條件（manifest + service worker） |
| 容器化 | Docker（multi-stage build） |
| 部署 | Render 免費方案 |

> ⚠️ **目前 JPA / Redis 依賴是「有設定、沒有使用」的狀態**：`application.yaml`
> 已經設定好 `spring.datasource.*` 與 `spring.data.redis.url`，`pom.xml` 也補上了
> 對應的 starter，但目前程式碼裡**沒有任何 Entity、Repository，也沒有任何地方呼叫
> Redis**。這兩個依賴目前只是先把連線打通、確保部署環境沒問題，實際的資料表與
> 快取邏輯留待後續功能才會用到。

## 系統架構

```mermaid
flowchart LR
    M[MockPriceGenerator<br/>每 3 秒排程] -- publishEvent Tick --> L[TickListener]
    L -- broadcast --> H[QuoteWebSocketHandler]
    H -- WebSocket 推播 --> F[前端 index.html]
    F -- SUBSCRIBE symbol --> H
```

目前是**單體應用內的事件流**，不經過任何外部訊息佇列：

1. `MockPriceGenerator` 用排程模擬報價變動，透過 `ApplicationEventPublisher` 發布 `Tick` 事件
2. `TickListener` 監聽 `Tick` 事件，組成 JSON payload
3. `QuoteWebSocketHandler` 依 `symbol` 找出訂閱該股票的所有 WebSocket session，逐一推播
4. 前端 `index.html` 建立 WebSocket 連線、送出 `SUBSCRIBE {symbol}` 指令，收到訊息後更新畫面

## 主要資料流

| 步驟 | 觸發方式 | 負責類別 | 說明 |
|---|---|---|---|
| 產生報價 | `@Scheduled(fixedRate = 3000)` | `MockPriceGenerator` | 對 3 檔股票（2330、2317、0050）做隨機漫步（±2%），算出新價格 |
| 發布事件 | 排程觸發後呼叫 | `MockPriceGenerator` → `ApplicationEventPublisher` | 把 `Tick` record 當作應用內事件發布，不落地到任何資料庫 |
| 監聽並轉換 | Spring 事件機制 | `TickListener.onTick(Tick)` | 把 `Tick` 轉成純文字 JSON（手刻 text block，未用 Jackson 序列化） |
| 推播 | 呼叫 `broadcast(symbol, json)` | `QuoteWebSocketHandler` | 依 `symbol` 找出所有訂閱該股票的 session，各自用一條 **virtual thread** 推送 |
| 前端訂閱 | WebSocket `onopen` | `index.html` | 連線建立後對每檔關注股票送出 `SUBSCRIBE {symbol}` 文字指令 |
| 前端渲染 | WebSocket `onmessage` | `index.html` | 解析 JSON，依「價格漲跌」套用紅漲綠跌樣式 |

> 目前沒有任何一步會寫入 PostgreSQL 或 Redis；報價資料只存在記憶體，應用程式重啟後
> 歷史價格會歸零重來。

## 模組結構

```
gtalent.stockpulse
├── StockPulseApplication.java     # 進入點，@EnableScheduling 啟用排程
├── config/
│   └── WebSocketConfig.java       # 註冊 /ws 端點，目前允許所有來源（CORS 全開）
├── gateway/
│   └── QuoteWebSocketHandler.java # WebSocket 連線管理、訂閱清單、推播邏輯
├── metric/
│   └── TickListener.java          # 事件監聽，未來指標計算邏輯的預留位置
├── mock/
│   └── MockPriceGenerator.java    # 模擬報價來源，未來會替換成真實行情 Ingestion Service
└── model/
    ├── Symbol.java                 # 股票代碼 + 市場別，不可變 record
    └── Tick.java                   # 單筆報價 record（symbol、price、volume、timestamp）
```

**分層邏輯**：`mock` 是資料來源層（未來會變成 `ingestion`）、`gateway` 是對外通訊層、
`metric` 是資料處理層（目前只是轉發，未來會加入技術指標計算）。彼此透過 Spring 事件
與建構子注入解耦，沒有互相寫死依賴。

## 核心類別說明

**`Symbol`（record）**：股票代碼 + 市場別，不可變值物件，`record` 自動提供
`equals()` / `hashCode()`，可直接當 `Map` 的 key。建構子驗證 `code` 不可為空。

**`Tick`（record）**：單筆即時報價（`symbol`、`price`、`volume`、`timestamp`），
`MockPriceGenerator` 產生、`TickListener` 消費。

**`MockPriceGenerator`**：監控清單目前寫死在程式碼裡（`2330`、`2317`、`0050`），
用 `ThreadLocalRandom` 做隨機漫步，每次波動基於上一次價格 ±2%，排程間隔 3 秒。
未來替換成真實行情時，下游邏輯完全不用改，只要新的 Ingestion Service 一樣發布
`Tick` 事件即可。

**`TickListener`**：目前**只做事件轉發**。類別註解說明「原本設計是 Kafka Consumer
的角色，MVP 階段改用 Spring 應用內事件監聽取代」——好處是不用額外的 Kafka 基礎設施，
**代價是只能在單一 JVM 內運作，無法水平擴展到多台機器**。

**`QuoteWebSocketHandler`**：內部維護 `Map<symbol, Set<WebSocketSession>>` 的訂閱關係
（`ConcurrentHashMap` 確保執行緒安全）。收到 `SUBSCRIBE {symbol}` 指令即加入訂閱清單；
連線關閉時自動清除該 session。**推播時每個 session 各自開一條 virtual thread**，避免
單一慢連線拖累其他訂閱者。推播失敗目前直接忽略、不記 log。

**`WebSocketConfig`**：把 `QuoteWebSocketHandler` 註冊到 `/ws` 路徑，
`setAllowedOrigins("*")` 目前允許所有來源。

## 前端（PWA）

`src/main/resources/static/` 底下是最精簡的 PWA：

| 檔案 | 用途 |
|---|---|
| `index.html` | 單頁應用，內嵌 CSS 與 JS，無框架、無建置流程 |
| `manifest.json` | PWA 基本資訊，但 `icons` 指向的 `/icon-192.png` **目前並不存在**於 `static/` 目錄，需要補上或修正路徑 |
| `sw.js` | 最簡化的 service worker，只註冊 `fetch` 事件監聽但不做任何快取邏輯 |

前端用 `location.origin.replace('http', 'ws')` 動態組出 WebSocket 網址，本機開發跟
正式部署不用改程式碼；斷線後 5 秒自動重試（Render 免費方案休眠時會用到這個機制）。

## 環境設定

`application.yaml` 讀取以下環境變數：

| 變數 | 來源 | 格式範例 |
|---|---|---|
| `DATABASE_URL` | Supabase 專案設定 | `jdbc:postgresql://db.xxxxxxxx.supabase.co:5432/postgres` |
| `DATABASE_USER` | Supabase | 通常是 `postgres`，若用連線池模式可能是 `postgres.xxxxxxxxxxxxxxxx` |
| `DATABASE_PASSWORD` | Supabase | 建立專案時設定的資料庫密碼 |
| `REDIS_URL` | Upstash 主控台 | `rediss://default:xxxxx@xxxxx.upstash.io:6379`（注意 `rediss` 兩個 s，代表 TLS） |
| `PORT` | Render 自動注入 | 不需手動設定 |

## 部署（Render）

`Dockerfile` 採 multi-stage build：第一階段用 JDK 完整編譯，第二階段只留 JRE + 編譯好
的 jar，縮小最終映像檔體積。實際監聽埠口依 `server.port: ${PORT:8080}`，Render 部署
時會自動注入 `PORT` 環境變數覆蓋預設值。

**目前部署狀態**：已成功部署於 Render，對外服務只有一個 WebSocket 端點 `/ws`，
沒有任何一般 REST API。

## 已知限制與技術債

- **沒有真實行情資料來源**：完全依賴 `MockPriceGenerator` 的隨機漫步模擬
- **JPA / Redis 只是設定好、未使用**：連線打通了，但沒有任何 Entity/Repository 或快取邏輯
- **無指標計算與評分邏輯**：`metric` 套件目前只做事件轉發
- **單體事件流無法水平擴展**：拿掉 Kafka 改用 Spring 應用內事件，只能在單一 JVM 裡運作
- **WebSocket CORS 全開**，正式環境應改白名單
- **推播失敗被靜默忽略**，未記錄任何錯誤 log
- **監控清單寫死在程式碼裡**，無法動態新增/移除關注股票
- **`manifest.json` 引用的圖示檔案路徑不存在**
- **沒有任何自動化測試**覆蓋核心邏輯

## 後續規劃

1. 用真實行情 API 取代 `MockPriceGenerator`，成為獨立的 Ingestion Service
2. 在 `metric` 套件加入實際的技術指標計算
3. 補上 `FundamentalsRepository`，透過 Supabase 儲存與查詢基本面資料
4. 視需求評估是否導入真正的訊息佇列（Kafka）以支援多機水平擴展
5. 補上 Redis 快取邏輯（例如快取最新報價、熱門股清單）
6. 修正 PWA manifest 圖示路徑，並考慮補上真正的離線快取邏輯
