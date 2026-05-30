# AndrOBD 重構藍圖（有優先序）

## 總評

目前架構最大的結構性問題是**雙 CommService 實例**：MainActivity 與 ObdBackgroundService 各自持有並管理一個 CommService，透過一個 `volatile sActiveInstance` 試圖協調，但這只是 band-aid，無法消除 TOCTOU 競態——兩者可同時對同一個 ELM327 發起連線，造成裝置 flapping、隨機斷線。這個根因衍生出至少六條 issue（雙實例競態、TOCTOU、connectBtDevice 不對稱檢查、前景/背景交接缺失、Handler 重複收訊、誰是 source of truth），應視為**單一架構工作**整體設計，不可零碎補。

次要結構問題是 **god-class**：MainActivity 2692 行身兼連線/UI/檔案/設定/選單/模式/Service 生命週期七職，以及 **CommService.elm 的 public static final 單例**被 UI 各處直接存取、跨多執行緒無同步。

但好消息是：**大量真實 bug 屬於純防禦性的 NPE/邊界 guard，零行為改變、可單獨 commit、且能由 library 的純 JUnit 在 CI 驗證**。這正是務實的起手點——先補護網，再動架構。本報告刻意把「只記 warning / 不 throw」「只加 guard / 不改型別」這類安全子集與原 finding 中過重的提案（DI 重寫、char[]→byte[]、throw 中斷載入）區分開來。

---

## 一、立即可做（低風險，可單獨 commit + CI 驗證）

這些改動全部零或近零行為改變，多數可由 `library` 模組的純 JUnit 測試在 CI 驗證（不需 Android SDK / 模擬器）。建議每項一個小 commit。

### 1. NRC 未知碼 NPE + 反應記錄（合併修）
- **檔案**：`library/.../ObdProt.java:567-603`
- **動作**：`NRC.get(nrcCode)` 後加 null fallback（`if (nrc == null) nrc = NRC.IGNORE;` 或回傳 `String.format("Unknown NRC: 0x%02X", nrcCode)`），第 569 行 `nrc.toString()` 與第 575 行 `switch(nrc.react)` 才不會 NPE。同一個 switch 順手在 SKIP/IGNORE 分支加 `log.info("NRC Reaction: " + nrc.react)`。
- **為什麼**：車輛可回傳 0x00–0xFF 任意碼，enum 只列約 17 個，未知碼直接 NPE 中斷整個協議迴圈、斷線。
- **效益**：消除一類真實崩潰；增加診斷可觀測性。
- **風險**：極低，只需慎選 fallback react（建議 IGNORE）。
- **CI 驗證**：✅ library JUnit 可直接餵未知碼斷言不崩。

### 2. BtCommService.write() NPE guard
- **檔案**：`androbd/.../BtCommService.java:212-216`
- **動作**：`mBtWorkerThread.write(out)` 外包 `if (mBtWorkerThread != null) { ... } else { log.warning(...); }`。
- **為什麼**：stop() 後（mBtWorkerThread=null）或 OFFLINE 時觸發送指令即 NPE，背景自動重連中尤其常見。
- **效益**：消除 OFFLINE 送指令崩潰。
- **風險**：零（純防禦）。
- **CI 驗證**：⚠️ 僅 assembleDebug 確認編譯（屬 app 模組）。

### 3. CommService Handler / Context null guard（合併「setState NPE」+「Missing Handler Null Checks」兩條同根因）
- **檔案**：`androbd/.../CommService.java:126,145,186-213`；`androbd/.../BtCommService.java:79-98`
- **動作**：`setState/connectionFailed/connectionLost/connectionEstablished` 中所有 `mHandler.obtainMessage(...)` 一律 `if (mHandler != null)` guard；連帶 `connectionFailed/Lost` 內用到的 `mContext` 也 null guard。
- **不要做**：finding 提到的 `WeakReference<Handler>`——會引入新的 GC 時序 bug。
- **為什麼**：存在 parameterless / null-handler 建構子路徑；Activity 銷毀後背景重連發訊息即崩潰。
- **效益**：消除背景重連 / Activity 已銷毀情境的崩潰。
- **風險**：零。
- **CI 驗證**：⚠️ 編譯驗證。

### 4. EcuDataItem.physFromBuffer() 字串擷取邊界
- **檔案**：`library/.../EcuDataItem.java:273-275`
- **動作**：迴圈前加 `if (ofs >= buffer.length) return "n/a";`，把 `while(buffer[ofs+padChars]==0)` 改成有界 `for (int i=0; i<bytes && ofs+i<buffer.length; i++)`。
- **為什麼**：越界 AIOOBE 目前被 try/catch 靜默吞成 n/a，VIN/CAL-ID 可能被無聲截斷。
- **效益**：避免無聲字串截斷、保留可診斷的 warning。
- **風險**：低——但改動 padding 邏輯**可能改變既有字串輸出**，務必先補 VIN 類 PID 的 JUnit 回歸測試再改。
- **CI 驗證**：✅ library JUnit。

### 5. getParamInt() 邊界檢查（只加 bounds，不改型別）
- **檔案**：`library/.../ProtoHeader.java:256-268`
- **動作**：加 `if (start + len > buffer.length) throw new IndexOutOfBoundsException(...)`；`len==0` 取 `buffer.length-start` 前先驗 `start < buffer.length`。
- **不要做**：把 `value` 改成 `long`——會改回傳型別 `Integer→Long`、牽動呼叫端，且實際 PID 最多 4 byte，int 容得下，所謂 overflow 不存在。
- **效益**：消除 silent buffer over-read。
- **風險**：低。
- **CI 驗證**：✅ library JUnit。

### 6. connectAttempts 改 AtomicInteger
- **檔案**：`androbd/.../ObdBackgroundService.java:287,326,336,358,109`
- **動作**：`int connectAttempts` → `AtomicInteger`，`++/--` → `getAndIncrement/getAndDecrement`。
- **為什麼**：onStartCommand 與 reconnectHandler 共享（雖實務上多在 MainLooper 同執行緒，競態機率低）。
- **效益**：乾淨的小改進、消除潛在 lost update。
- **風險**：零。
- **CI 驗證**：⚠️ 編譯驗證。

### 7. mCommService 改 volatile
- **檔案**：`androbd/.../MainActivity.java:264`（及讀寫處 778/924/1076/2116）
- **動作**：`private CommService mCommService` → `private volatile CommService mCommService`。
- **不要做**：過度的 synchronized accessor。
- **效益**：跨核心記憶體可見性保證，消除罕見 NPE。
- **風險**：零。
- **CI 驗證**：⚠️ 編譯驗證。

### 8. isOtherInstanceConnected 改走 getState()
- **檔案**：`androbd/.../CommService.java:81-86`
- **動作**：把直接讀 `active.mState` 改為呼叫已同步的 `active.getState()`，補上 happens-before。
- **為什麼**：sActiveInstance 雖 volatile，但讀到 instance 後直接讀 mState 缺同步邊界。
- **效益**：單行修補可見性漏洞（是雙實例治理的前置小步）。
- **風險**：零。
- **CI 驗證**：⚠️ 編譯驗證。

### 9. CI 加 library 測試 + lint（讓上面所有 fix 真正被驗證）
- **檔案**：`.github/workflows/android.yml`、`android_beta_apk.yml`
- **動作**：加 `./gradlew :library:test`、`./gradlew lint --continue`。
- **前置注意**：若現有 library 測試是紅的、或 lint 有大量既有 error，需先設 lint baseline 或 `abortOnError false`，否則會擋住 build。
- **效益**：上述 1/4/5 等 library fix 從此有 CI 護網；這是整份藍圖的「驗證基礎設施」。
- **風險**：低（只動 workflow yml）。
- **CI 驗證**：✅ workflow 本身即驗證對象。

### 10. 測試載具：擴充 library 純 JUnit（非 app Espresso）
- **檔案**：`library/src/test/...`（新增）
- **動作**：對 NRC / getParamInt / physFromBuffer / CSV 載入加 pure-JUnit。**app 層 Espresso/Robolectric 列為長期**——在無本機 SDK、CI 只跑 assembleDebug 的前提下，instrumented 測試需模擬器、CP 值低。
- **效益**：上述各 fix 的回歸護網。
- **風險**：零（純新增）。
- **CI 驗證**：✅。

### 11. build.gradle 無痛清理子集
- **檔案**：`androbd/build.gradle`
- **動作**：testInstrumentationRunner `android.support.test.runner.AndroidJUnitRunner` → `androidx.test.runner.AndroidJUnitRunner`（app 根本沒 instrumented test，這行幾乎無作用，純清理）；釘住依賴版本（speedviewlib 等）。
- **不要做**（此階段）：minSdk 17→26（會切掉真實舊機使用者，屬產品決策，獨立處理）；keystore 從 build.gradle 搬走（要小心別弄壞 CI base64 簽章流程）。
- **效益**：去除 deprecated、避免版本漂移。
- **風險**：低。
- **CI 驗證**：⚠️ 編譯/組裝驗證。

---

## 二、中期（需多檔協調，行為需真機驗）

CI 此區多半只能編譯驗證，連線/背景/時序行為必須真機測。

### 12. NetworkCommService 連線執行緒清理（局部，不要全域統一）
- **檔案**：`androbd/.../NetworkCommService.java:135-172,179`
- **動作**：`connect()` 前若 `connectThread != null && isAlive()` 先 `interrupt()+join(timeout)`；`ConnectThread.run()` 加 try-finally 清理；`Socket.setSoTimeout()` 限制 blocking connect。
- **不要做**：finding 中「三 transport 統一改 ExecutorService+Future」——那是重寫整個連線層、極易引入新斷線 bug。BtConnectThread 靠 `socket.close()` 打斷 blocking connect 其實有效，不必動。
- **效益**：消除 ghost ConnectThread 累積、socket/port 洩漏。
- **風險**：join/timeout 設太短會誤殺正在連線的 thread 造成連不上。
- **CI 驗證**：⚠️ 僅編譯；真實 socket 行為需真機。

### 13. 自動重連 postDelayed 去重
- **檔案**：`androbd/.../ObdBackgroundService.java:290,305,337,359,369`
- **動作**：收斂成單一 `scheduleNextRetry()` + `volatile boolean reconnectScheduled` flag，避免多路徑（onStartCommand/scheduleReconnect/BT-not-ready/finally）堆疊。
- **效益**：消除重連 thrashing、降低耗電。
- **風險**：`removeCallbacksAndMessages(null)` 會清掉 reconnectHandler 上**所有** callback，重構時漏掉某路徑會讓自動重連直接失效。
- **CI 驗證**：⚠️ 編譯；背景重連行為需真機長時間測。

### 14. Service 監聽 SharedPreferences 變更
- **檔案**：`androbd/.../ObdBackgroundService.java`（onCreate/onDestroy）
- **動作**：註冊 `OnSharedPreferenceChangeListener`，響應 `pref_auto_connect` / `pref_continuous_retry`；onDestroy 反註冊。
- **為什麼**：使用者關掉 auto-connect 後背景仍重連，設定被忽略。
- **效益**：設定即時生效。
- **風險**：低（記得反註冊避免 leak）。
- **CI 驗證**：⚠️ 編譯；「改設定後背景立即停止重連」需真機。

### 15. 危險 ObdBackgroundService PvChangeListener 殘留
- **檔案**：`androbd/.../ObdBackgroundService.java:98,135`
- **動作**：註冊/反註冊改為 idempotent；考慮 WeakReference adapter 讓 Service 被 GC 後自動脫鉤。
- **為什麼**：系統直接 kill Service 未走 onDestroy 時，listener 殘留在 static `PidPvs` map，下次 `pvChanged` 對死 Service 呼叫。
- **效益**：消除背景被殺後的崩潰/leak。
- **風險**：WeakReference 實作不當會讓 listener 過早被 GC → 背景資料停更（弄壞背景監控核心）。
- **CI 驗證**：❌ Service kill 情境無法 CI 驗。

### 16. CSV 載入期警告彙總（只 warn，不 throw）
- **檔案**：`library/.../EcuDataItems.java:153-185`
- **動作**：預載 conversions.csv，pids.csv 引用的 FORMULA key 找不到時**彙總 warning**並記錄 conversion 型別。
- **絕對不要做**：finding 提案的「找不到就 throw 中斷載入」——任何 CSV typo 會讓 app 啟動即崩，比現狀更糟。也不要換 Jackson/Gson（大改、勿做）。
- **效益**：CSV 編輯錯誤在載入期可見，而非執行期靜默 null。
- **風險**：低（純觀測性）。
- **CI 驗證**：✅ library JUnit 可餵壞 CSV 斷言 warning。

### 17. PID 值範圍驗證（僅在 min/max 明確存在時）
- **檔案**：`library/.../EcuDataItem.java:258-265`
- **動作**：phys 轉換後，**只在 CSV min/max 明確存在時**驗範圍，否則一律放行。
- **為什麼**：擋住里程表 429M km 類異常顯示。
- **風險**：很多 PID 的 min/max 為空或設保守，若無條件套用會把原本正常讀數變成錯誤的「out_of_range」——**弄壞既有正常顯示**。必須先用 library 測試覆蓋大量既有 PID 確認零 regression。
- **CI 驗證**：✅ library JUnit（前提是先建回歸基準）。

### 18. NetworkCommService connect(Object, boolean) port 解析
- **檔案**：`androbd/.../NetworkCommService.java:188-191`
- **動作**：讓 boolean 多載解析 `host:port`，或標 deprecated。注意實際連線路徑多走 `connect(host, int port)` 多載、此多載幾乎不被真正呼叫。
- **效益**：支援自訂 port 的 WiFi OBD adapter。
- **風險**：若有隱藏呼叫端依賴 23/22 預設會被改掉。價值中低。
- **CI 驗證**：⚠️ 編譯。

### 19. 狀態轉移驗證（只記 warning，不 throw）
- **檔案**：`androbd/.../CommService.java:126`
- **動作**：加 `isValidTransition(from, to)` 矩陣，非法轉移**只 log.warning**。
- **絕對不要做**：finding 提案的「非法就 throw」——`CONNECTED→CONNECTING`（重連）實際會發生，throw 會崩潰、弄壞重連與斷線流程。
- **效益**：觀測性、為後續雙實例除錯鋪路。
- **風險**：低（只記錄）。
- **CI 驗證**：⚠️ 編譯。

---

## 三、大型 / 高風險（標明風險與分階段）

此區全部觸及連線核心或 app 生命週期，**CI 無法驗競態 / Service 生命週期 / 前景背景時序，必須真機長時間測**。前提：**先把第一區護網全部落地**。

### A. 統一連線 ownership（雙實例根因群——合併為單一架構工作）

涵蓋以下原本被拆成多條的同根因 finding，應整體設計：
- Dual Instance Architecture（critical）
- Dual-Service Race Condition on BT（critical）
- TOCTOU between sActiveInstance check and connect()
- Race between connectBtDevice() and connectToLatestDevice()
- 誰是 source of truth
- CommService Handler 重複收訊
- 前景/背景連線交接缺失
- Service lifecycle（START_STICKY/bind）耦合

**涉及檔案**：`CommService.java`、`MainActivity.java`、`ObdBackgroundService.java`。

**終局方向（推薦選項 A：Service 為單一 source of truth）**：ObdBackgroundService 持有唯一 CommService，MainActivity 前景時「借用」並訂閱 Service 狀態，不自己 new CommService。

**分階段做法（每階段一個可驗 commit）**：

1. **階段 0（已在第一區）**：§8 isOtherInstanceConnected 走 getState()。
2. **階段 1（中風險 band-aid，可較快落地）**：`MainActivity.connectBtDevice()/connectNetworkDevice()` 起頭加**對稱的** `isOtherInstanceConnected` 檢查（目前只有 Service 側有檢查，MainActivity 側完全沒有）。這只是緩解、非根治，但能立刻降低 flapping 機率。CI 僅編譯。
3. **階段 2**：引入跨實例的「連線許可」——`AtomicReference<CommService>` + CAS，或 `DeviceConnectionManager` singleton 序列化「檢查+設 CONNECTING+起 thread」為原子操作，消除 TOCTOU。**風險：做錯會 deadlock 或讓正常連線被擋。**
4. **階段 3**：把 CommService 生命週期收斂到 Service。MainActivity 改訂閱（Observer / ServiceStateListener），移除自己的 mCommService。Handler 重複收訊問題此時自然收斂。
5. **階段 4**：修正 Service 生命週期——分離 start 與 bind（先 `startForegroundService` 再 `bindService`）、Activity onDestroy 只 unbind 不 kill Service（明確 disconnect 才 `stopService`）、`connectAttempts` 不在每次 onStartCommand 重置。
6. **階段 5**：前景/背景交接——onPause/onResume 通知 Service，Service 在 Activity 背景時主動接手（而非被動 defer 15s）。

**風險總結**：觸及連線啟動核心，CI 無法驗競態；生命週期改動牽涉「使用者退出後 Service 是否該存活」（產品決策）、低記憶體被殺處理、電量行為。階段 4/5 屬最高風險，必須真機長時間測。

### B. ElmProt static singleton 同步化 / 去耦（合併三條同根因）

涵蓋：Shared ElmProt without thread-safe access、ElmProt static singleton、Static CommService.elm accessed from UI。

**短期安全子集（moderate）**：對 `ObdProt.pidSupported`（Vector）、`cmdQueue`、telegram writer list 換成 `Collections.synchronizedList` / `ConcurrentHashMap`。
- **不要做**：finding 提案的 `synchronized(CommService.class)` 包住**所有** elm 存取——elm 內部回呼又取鎖會 deadlock。

**長期（risky，需先有測試護網）**：把 `CommService.elm` 從 `public static final` 改成注入式 instance（IObdService 介面 + ServiceLocator/ViewModel）。
- **風險**：MainActivity 數十處 `CommService.elm.xxx`、FileHelper、DashBoardActivity、ChartActivity 都直接存取；且「兩個 CommService 共享同一 elm」正是現有資料流前提，改成 per-session 會弄壞前景/背景資料一致性。**必須在 A（統一 ownership）完成後再做**，屆時只有單一 CommService，elm 去靜態化才安全。

### C. MainActivity god-class 拆解（最高風險、最長期）

- **檔案**：`MainActivity.java`（2692 行，7 個 listener、20+ static、80+ method）。
- **嚴禁一次大重構**——CI 只能編譯，任何遺漏的 listener / 狀態交接會靜默弄壞模式轉移、前景背景連線、SharedPreferences 監聽。
- **分階段（一次只抽一個 manager + 真機回歸）**：
  1. **先抽風險最低的**：`FileIOManager`（FileHelper / save-load / 日誌）、`MenuController`（選單項 + visibility）——與連線/狀態解耦，最易驗。
  2. 次抽 `DataAdapterController`（4 個 adapter + 資料監聽）、`ModeManager`（MODE enum + 200 行 setMode）。
  3. **最後抽 `ConnectionManager`**——這必須與 §A 統一 ownership **一起設計**，不可獨立抽出。
- **目標**：MainActivity 2692 → 800-1000 行，只管 UI 佈局、事件分發、Service 訂閱。
- **效益**：可維護性、可測性。**收益偏長期**。

### D. Adapter 模板化（DRY，中風險、長期）

- **檔案**：`ObdItemAdapter` + Tid/Vid/Dfc/PluginDataAdapter。
- **動作**：抽 `BaseEcuAdapter` template method，子類只實作 `getViewContent()`。
- **風險**：直接影響 UI 呈現，CI 無法驗 list 顯示/recycling，回歸只能人眼。價值中等。

---

## 不建議行動的項目（finding 為偽 / 提案有害）

- **PvChangeListener Registration Imbalance（判定 is_real=false）**：`PvChangeListeners` 是 `Map<Listener,Integer>`，put 同一 listener 只覆蓋不累積，「重複註冊導致通知倍增」前提錯誤。據此改 `addPvChangeListener` 回傳 false 反而可能影響 eventMask 語意。
- **Package namespace mismatch（is_real=false）**：library 排除 `*.gui` 是刻意讓 library 純協議層，是合理模組邊界而非缺陷。重切 androbd-core 模組成本高收益低。
- **char[]→byte[] 全面改造（risky）**：横跨 StreamHandler/ProtoHeader/ElmProt/所有 telegram 路徑，極易破壞所有 PID 解析，且 finding 自承實務上 odometer/temperature 多用 unsigned。無證據有 PID 受影響，價值低。除非有完整 golden-vector 測試否則不要動。

---

## 建議的第一個 PR（最高 CP 值起手式）

**一個 PR：「Library NPE/邊界 guard + CI library 測試」**

內容：
1. NRC 未知碼 fallback（§1）
2. getParamInt 邊界檢查（§5）
3. physFromBuffer 字串邊界（§4，含 VIN 回歸測試）
4. 新增涵蓋上述三點的 library 純 JUnit（§10）
5. CI 加 `./gradlew :library:test`（§9，必要時先設 lint baseline）

**為什麼是它**：
- 全部在 `library` 模組、純防禦、零行為改變，是整份藍圖中**唯一能被 CI 完整驗證**的一批；
- 修的是會中斷協議迴圈 / 導致斷線的真實崩潰（NRC NPE 尤其）；
- 一次把「測試護網 + CI 驗證步驟」建起來，後續所有 library fix（CSV 警告、PID 範圍驗證）都能站在這個基礎上安全推進；
- 完全不碰連線核心、不碰 MainActivity、不碰 Service 生命週期——對「使用者正在開車用的 app」零風險。

把 app 模組的 NPE guard（§2 BtCommService.write、§3 Handler null guard）放**第二個 PR**，因為它們只能編譯驗證、無法 CI 跑測試，與第一個 PR 的「可驗證」性質分開。連線 ownership（§A）等護網建立、團隊對行為有信心後再啟動，且務必拆成多個小可驗 commit 漸進推進。