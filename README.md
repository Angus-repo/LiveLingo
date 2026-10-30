# 即時譯 LiveLingo

使用 Kotlin Multiplatform + Compose Multiplatform 的 Android／iOS 即時字幕翻譯 App。**0.1.7 新增 Android 音源選擇，並修正內容安全篩選通知被當成必須斷線的錯誤。可用 Microsoft Foundry 或 OpenAI GPT-Live，由 App 直接連線，不需自架後端。**

API key 由使用者自行輸入，Android 以 Keystore 保護加密金鑰並加密保存，iOS 以 Keychain 保存。原始碼、APK、逐字稿匯出及一般設定均不內建或保存明文 API key。

## 功能

| 功能 | 說明 |
| --- | --- |
| 直接連線 | Microsoft Foundry／OpenAI，模型與加密金鑰獨立保存 |
| 音源選擇 | 麥克風、裝置播放音訊、Microsoft Teams（嘗試擷取）；Android 播放音訊需 Android 10+ 與每次系統授權 |
| 收音狀態 | Android 顯示音量訊號；播放音訊持續約 15 秒無聲時提示檢查來源、音量或擷取限制 |
| 內容篩選處理 | 執行中收到 content_filter 時顯示片段不完整提示，保留仍可用的會話；服務正式結束時才釋放收音與連線 |
| 連線診斷 | 不開啟麥克風測試連線，顯示錯誤階段與可取得的 HTTP 狀態碼 |
| 來源語言 | 自動、英文、印度英文、繁體中文、日文、韓文；自動模式於收到字幕後顯示文字推定語言 |
| 目標語言 | 英文、印度英文、繁體中文、日文、韓文 |
| 背景知識 | 管理多組場景與術語，翻譯前選擇一組或不使用 |
| 即時字幕 | 原文在上、譯文在下；依時間由舊至新排列並自動捲至最新，文字不再按每段音訊換行，畫面不顯示時間戳 |
| 逐字稿 | 自動保存在裝置，保留原文與譯文各自時間戳，支援歷史記錄、重新命名與刪除 |
| 匯出 | SRT／WebVTT 保留時間戳，TXT 含閱讀段落與時間明細，JSON 保留原始片段及 AI 段落位置 |
| 繁中校正 | 目標選繁中台灣時，提示詞加本機 OpenCC 字詞資料轉換；保留數字與原始片段 |
| AI 語意分段 | 使用所選服務的文字模型判斷原文、譯文的段落位置；未設定時採基本句子分段 |
| 發言者 | 手動標記接下來收到的原文，或於停止後修正 |
| Android 背景翻譯 | 前景啟動錄音服務後，可切換 App／鎖定螢幕，從通知停止並儲存；iOS 仍為前景翻譯 |
| 翻譯策略 | 速度：優先即時／平衡；品質：忠實原文／自然流暢，工作階段開始前選定並保存到本機 |

「自動」是來源語言偵測，目標語言需明確選定。印度英文是口音與慣用語提示，並非另一個保證準確的辨識引擎。繁體中文使用台灣用語。

本版顯示字幕，不播放合成譯音。GPT-Live 仍會在服務端產生語音，App 使用其輸出逐字稿作為譯文，仍會有模型使用量。

## 開始使用

1. 在 Microsoft Foundry 中準備可用的 `gpt-live-1` 部署與配額。
2. 打開 App「設定」，填入三個欄位：

| 欄位 | 範例／用途 |
| --- | --- |
| Foundry 端點 | `https://YOUR-RESOURCE.openai.azure.com`，使用資源根端點 |
| 部署名稱 | `gpt-live-1`；如果部署使用自訂名稱，填入該名稱 |
| API key | 該 Foundry 資源的 API key |

3. 按「測試連線」檢查目前欄位。測試成功後按「儲存連線設定」，再返回「即時翻譯」。測試本身不會儲存欄位。
4. 選擇音源、來源／目標語言與本次背景知識。開始翻譯後依系統提示授權；播放音訊模式須再同意音訊擷取，然後切換到來源 App 播放。
5. 停止後可到「逐字稿」檢視、修正發言者與匯出字幕。

不需要服務網址、relay token、Docker、Java 後端或另外申請伺服器。App 會將 HTTPS 資源端點轉成對應的 WSS GPT-Live 工作階段路徑。不要填入 Foundry Portal 網頁、專案管理網址或舊版 `/realtime` API。

API key 儲存後不會重新顯示。只修改部署名稱時，API key 留空可保留原值；變更資源端點時必須重新輸入對應 key，避免將舊 key 傳給另一個資源。若按「移除 API key」，再次翻譯前須重新輸入。

## 0.1.7 音源選擇

在「即時翻譯 → 準備這一場對話 → 音源」選擇，停止後才能切換。選擇會保存，工作階段及 TXT／JSON 也記錄實際選定音源；舊設定與逐字稿預設為麥克風。

| 音源 | 用法與限制 |
| --- | --- |
| 麥克風 | 收取目前麥克風的聲音，包含現場對話或外放音訊；延續原有背景與鎖螢幕收音 |
| 裝置播放音訊 | 只收取裝置上允許擷取的媒體播放聲音，不混入麥克風；其他 App 同時播放的媒體也可能被收取 |
| Microsoft Teams（嘗試擷取） | 以 Teams 的 UID 篩選，只接收同一個使用者設定檔中的 Teams 允許擷取的音訊。未安裝／停用時提示且不能開始，不會改抓其他 App 或偷偷開麥克風 |

**Teams 選項不代表一定能錄到 Teams 會議。** Android 公開 AudioPlaybackCapture 只允許 `USAGE_MEDIA`、`USAGE_GAME`、`USAGE_UNKNOWN` 且允許其他 App 擷取的播放器；`USAGE_VOICE_COMMUNICATION` 等通話用途不在允許範圍內。Teams 若使用通話用途或禁止錄製，便無法透過此功能取得會議音訊。此版本未串接 Microsoft Graph、Teams 媒體機器人，也不能擷取另一台電腦上的 Teams。可改以麥克風收取外放聲音；若同機 Teams 占用麥克風，可能需要由另一台裝置外放。

Android 10+ 的播放擷取需要錄音權限，以及每次開始時的系統螢幕分享／錄製授權。LiveLingo **只建立音訊擷取，不建立虛擬顯示器、不讀取或傳送畫面**。Android 14+ 的流程為先取得同意，再啟動 `mediaProjection` 前景服務，最後使用一次性授權建立擷取。Teams 與 LiveLingo 必須在同一個個人／工作設定檔，不能跨設定檔取得音訊。

系統撤回授權、其他 App 開始分享或鎖定螢幕時，播放音訊擷取可能停止；App 會結束收音並保留已收到的字幕。每次開始須重新授權，取消或拒絕授權時不連接模型服務。音量條僅表示有音訊訊號，不能證明 Teams 會議可擷取或有辨識出人聲。持續約 15 秒無聲只顯示提示，不將靜音直接當作被封鎖，也不自動改變音源。

iOS 與 Android 8／9 仍只提供麥克風。版本 `0.1.7`／versionCode `8`；驗證結果見 [BUILD_STATUS.md](BUILD_STATUS.md)。

實作依據：[Android 音訊播放擷取](https://developer.android.com/media/platform/av-capture)、[前景服務類型與授權順序](https://developer.android.com/develop/background-work/services/fgs/service-types#media-projection)、[MediaProjection 終止與資源回收](https://developer.android.com/media/grow/media-projection#resource-recovery)。

### 0.1.7 content_filter 修正

0.1.6 將每個 `error` 事件都丟出例外，造成原本可能仍可用的 GPT-Live 工作階段被 App 提前關閉，並錯誤提示檢查部署或權限。

- 已啟動的會話收到 `content_filter` 時，顯示非阻擋式通知並繼續讀取原連線的後續事件。後續有字幕時正常呈現，不補造服務阻擋的片段。
- 收到 `session.closed` 且 `reason=content`、斷線或逾時時停止；尚未收到 `session.started` 的內容篩選錯誤仍阻擋啟動，避免在模型不可用時錄音。
- 診斷使用 `CONTENT_FILTER`，不再把它當作一般部署或金鑰錯誤；串流事件沒有 HTTP 回應碼時明示不適用。HTTP 啟動失敗仍保留實際 HTTP 狀態。
- 篩選通知次數與逐字稿一起儲存，歷史、TXT、JSON 顯示部分片段可能不完整；原有字幕及時間戳保留。
- 不自動重連、重送被阻擋音訊或改變服務端篩選設定。若服務終止一般對話且反覆發生，可將診斷交由 Foundry 資源管理者確認；App 無法判定或消除每個服務端誤判。

依據：[GPT-Live 內容審核與工作階段生命週期](https://developers.openai.com/api/docs/guides/live-conversations#handle-moderation)、[Microsoft Foundry GPT-Live 錯誤與結束事件](https://learn.microsoft.com/en-us/azure/foundry/openai/gpt-live-reference#error-handling)。

## 0.1.6 繁中、AI 分段與服務切換

- 修正即時字幕把換行全部轉成空白的問題。原文、譯文、歷史閱讀及 TXT 都能呈現段落；音訊分片不會被當成句子。
- 繁中台灣以嚴格提示詞加離線字詞轉換處理。使用 OpenCC 授權字典，以最長詞組匹配，再套用台灣詞彙；不是重寫內容的 AI 校稿。字幕中的數值不做修正或推算。原始 provider fragments 與時間戳繼續保留在 JSON。
- 設定可選 Microsoft Foundry／OpenAI，兩者模型名稱與加密 API key 獨立保存。切換不會刪除另一服務的設定；舊版 Foundry 設定自動沿用，工作階段中不可切換服務。
- OpenAI 預設 `gpt-live-1`，使用官方 `wss://api.openai.com/v1/live/sessions` 與 Bearer 認證。Foundry 使用資源 `/openai/v1/live/sessions` 與 `api-key` 標頭。兩者皆為 GPT-Live 協定，不是一般 Realtime 模型。

### AI 分段設定

設定開啟「AI 語意分段」，填入相容 Chat Completions 與 JSON object 的**文字模型**後儲存：

| 服務 | AI 分段文字模型 |
| --- | --- |
| Microsoft Foundry | 在同一資源部署文字模型（例如 `gpt-4.1-mini`），填入實際部署名稱；預設留空，避免假設使用者已有部署 |
| OpenAI | 預設 `gpt-4.1-mini`，可改為帳號可使用的相容模型 |

GPT-Live 是語音模型，不能填在 AI 分段文字模型欄。分段只將字幕送至所選服務，使用該服務金鑰，另計文字模型用量。

約每 20 秒與停止時，將每個軌道最近最多 40 個完整句子、約 6,000 字元送出。AI 只回傳「哪些句子之後換段」的編號；App 驗證編號後插入空白行，不接受模型改寫原文、數字或譯文。尚未完成的句子立即顯示，後續再整理。保留既有模型換行；沒有設定、暫時失敗或尚未完成 AI 判斷時，先以 2–3 句的基本分段顯示，畫面明示狀態。

「測試連線」只測 GPT-Live。文字模型於有字幕時使用，失敗不停止錄音或丟棄逐字稿。AI 段落位置與來源快照一起保存，亂序或新增文字造成快照不符時不套用舊位置。

### OpenAI 開始使用

選「OpenAI」，填入可使用 GPT-Live 的模型名稱與 API key，儲存後測試連線。端點固定為官方 API 網域，不能輸入代理主機。來源、目標語言、背景知識、速度、品質與 Android 背景服務沿用原本操作。

本次 Android 為 `0.1.6`／versionCode `7`。建置、測試、簽章驗證與未驗證項目見 [BUILD_STATUS.md](BUILD_STATUS.md)。

實作依據：[OpenAI GPT-Live WebSocket](https://developers.openai.com/api/docs/guides/voice-websockets?api=live)、[OpenAI GPT-Live 工作階段](https://developers.openai.com/api/docs/guides/live-conversations)、[Foundry v1 Chat Completions](https://learn.microsoft.com/en-us/azure/foundry/openai/latest?view=foundry-classic)。OpenCC 授權與版本見 `third_party/OpenCC/`，授權亦包含於 App 資源。

## 0.1.5 語言推定、字幕閱讀與翻譯策略

- **自動偵測語言**：當來源語言為「自動偵測」時，原文卡片顯示「自動偵測（英文）」等。此文字由 App **根據已收到的原文字幕推定**，並非 Foundry 回傳的語音語言識別碼；僅有少量文字、拉丁字母多語、日文純漢字與口音時無法可靠辨識。印度英文與一般英文無法單靠逐字稿區分。提示會有不確定標示，並可能在接收更多文字後變動。
- **字幕排版**：Foundry 的 `session.input_transcript.delta` 與 `session.output_transcript.delta` 是音訊分片，並非句子。即時畫面將原始片段串接成一份可自然換行的文章，不再依片段插入空白行。字幕原始片段、時間戳與 SRT／WebVTT／TXT／JSON 匯出不改動。
- **翻譯速度**：可選「優先即時」或「平衡」（預設），由 prompt 指示模型將短語盡早譯出或保留短距離上下文。這並非可保證的延遲上限，設定會於下一場工作階段生效。
- **翻譯品質**：可選「忠實原文」（預設）或「自然流暢」，前者特別強化金額、單位、否定、語意邊界，後者優先自然語法但同樣禁止補充未說出的內容。品質仍可能受模型與收音影響。
- **不支援的假參數不傳送**：GPT-Live 的 `session.start` 只使用官方支援的 model、instructions、audio.output.voice、delegation。沒有將一般 Realtime API 的 VAD、temperature、reasoning_effort 等項目塞入嚴格的 GPT-Live session 物件。
- 版本號更新至 `0.1.5`／versionCode `6`，維持原 App ID 與舊版相容的逐字稿／金鑰儲存格式。

## 0.1.4 即時字幕與 Android 背景翻譯

即時字幕頁面先顯示「原文」再顯示「譯文」。每個字幕軌道依時間從上往下排列，不限最新三段且自動捲動到最後一段。即時顯示不含時間戳；「逐字稿」檢視及 SRT／WebVTT／TXT／JSON 匯出仍包含時間資訊，不改變既有記錄格式。

Android 會先在可見畫面取得麥克風權限並啟動 `microphone` 型態前景服務，再連上 Microsoft Foundry。收音、WebSocket 與即時寫入逐字稿由不依賴 Activity 的工作階段管理，切換其他 App、按 Home 或鎖定螢幕不會主動停止翻譯。錄音時有通知與「停止並儲存」操作。停止後仍會嘗試完成最後音訊的送出與逐字稿保存。

**限制與安全性**：必須在 App 畫面中手動開始；無法從背景自動啟動麥克風，也不會在重新開機／程序遭系統終止後自動恢復。Android 仍可能因為使用者強制停止、來電、麥克風被其他 App 占用、網路失效或系統限制中止翻譯。通知權限被拒絕時，部分 Android 版本可能不在通知抽屜顯示通知，仍可從 App 停止；建議允許通知。iOS 目前仍只在前景翻譯，不會假裝 iOS 已支援背景收音。

## 0.1.3 認證修正

修正 Azure GPT-Live WebSocket 的 API key 認證：現在以 `api-key` 標頭傳送資源金鑰。0.1.1／0.1.2 將資源 key 放在 `Authorization: Bearer`；LiveKit 的 Azure GPT-Live 實作記錄此用法會引發帶 `?api-key=` 的 HTTP 302 轉址。`Bearer` 用於 Entra 存取權杖，不能直接將其他 HTTP API 的認證範例套用在此流程。

修正套用於 Android 與 iOS。端點、部署名稱及已加密儲存的 key 可繼續使用；App 仍不跟隨轉址，也不把 key 放進 URL。診斷報告會顯示「認證方式：api-key 標頭」，不包含金鑰值。

實作依據：[LiveKit Azure GPT-Live 認證程式碼](https://github.com/livekit/agents/blob/78c154d6edfff305b755ede26f42f11cf280bf4e/livekit-agents/livekit/agents/llm/_realtime/gpt_live.py#L580)。此修正針對已收到的 302 握手診斷；實際資源的連線結果仍需在裝置上驗證。

## 0.1.2 連線與診斷功能

修正 Android 的 WebSocket 設定：Ktor 3.3.1 的 OkHttp 引擎不支援變更 `maxFrameSize`，舊版會在握手成功後拋出例外。Android 現在保留引擎預設值，仍由共用接收程式檢查 1 MiB 訊息上限。iOS 的 Darwin 引擎支援此設定，因此保留原有限制。

「測試連線」會建立並關閉一個短暫 GPT-Live 工作階段，不啟用麥克風，也不傳送背景知識或逐字稿；建立會話仍可能產生服務使用量。測試使用目前表單，API key 留空時只會使用同一資源已儲存的 key。

若連線失敗，可用「複製診斷」或設定頁的「複製／分享」取得報告。報告包含失敗階段、App 診斷碼與可取得的 HTTP 狀態、服務錯誤代碼及請求識別碼，不包含 API key、背景知識、逐字稿或原始錯誤內容。資源名稱與部署名稱也會遮蔽；網路尚未收到 HTTP 回應時，不會有 HTTP 狀態碼。

從 0.1.1 使用相同簽章覆蓋安裝可保留已儲存的連線設定與資料，不必先移除 App。

## 從 0.1.0 更新

保持相同 App ID 與開發簽章，可直接覆蓋安裝。原有背景知識、逐字稿、來源及目標語言設定會保留。

0.1.0 的自架服務網址與存取權杖不會被當成 Foundry 認證。更新後請重新填入 Foundry 端點、部署名稱及 API key；舊 relay token 於載入與遷移成功後清除。

## 專案與建置

- `core/`：語言、背景知識、GPT-Live 事件解析與字幕匯出。
- `shared/`：Compose 介面、會話控制、Foundry WebSocket、本機保存與平台錄音介面。
- `androidApp/`：Android 主機、權限與生命週期。
- `iosApp/`：SwiftUI 主機、AVAudioEngine、Keychain、系統分享及 Xcode 專案。
- `.github/workflows/build.yml`：共用測試、Android APK 與 iOS 編譯。

Android 需求：JDK 17、Android SDK 36，最低 Android 8／API 26。

```bash
./gradlew :core:jvmTest :shared:testDebugUnitTest :androidApp:assembleDebug
```

輸出：`androidApp/build/outputs/apk/debug/androidApp-debug.apk`。

只有 JDK、沒有 Android SDK 時，仍可執行核心測試：

```bash
./gradlew -Pheadless=true :core:jvmTest
```

iOS 需要 macOS 與相容 Xcode。開啟 `iosApp/LiveLingo.xcodeproj`，依 [iOS 說明](iosApp/README.md) 建置；實體 iPhone 安裝需設定自己的 Apple 開發者簽章。

Android 正式簽章與 GitHub Actions 開發簽章設定見 [Android 說明](androidApp/README.md)。不要將 keystore 或真實 API key 提交到 GitHub。

## 連線、錄音與資料處理

- App 以 WSS 直接連接所選服務，沒有中繼後端。Foundry 使用 `api-key`，OpenAI 使用 `Authorization: Bearer`；金鑰不放在 URL。
- 錄音為 24 kHz、單聲道、PCM16 little-endian。iOS 會從裝置實際取樣率轉換。
- 收到 `session.started` 後才開始收音；停止時送出最後已擷取的音訊與 `session.close`，等候最後字幕及 `session.closed`。
- Android 在前景建立麥克風前景服務後可持續背景收音，切換 App 或鎖定螢幕不會主動停止；iOS 目前仍只在前景收音。來電、音訊中斷、系統強制停止、斷線或錯誤可能中止，且不會自動重啟錄音或重播舊音訊。
- 未收到任何服務事件達 90 秒時視為連線異常並停止；這是 App 的逾時策略，並非模型的回覆時限保證。
- 不保存原始錄音檔。音訊與選定背景知識只傳至選定服務；啟用 AI 分段時，最近字幕也送至該服務的文字模型。
- 逐字稿與背景知識保存在 App 專用空間；API key 使用系統保護的安全儲存區，與一般資料分開。
- 停用重新導向，避免認證資訊跟隨轉址；Foundry 僅接受受支援的 Azure 資源端點，OpenAI 僅接受官方 API 主機。

## 字幕與發言者限制

GPT-Live 回傳的是帶時間範圍的串流片段，沒有每個字的精準對齊或多人 `speaker_id`。App 合併相鄰片段方便閱讀，原始片段仍保存於 JSON。

原文與譯文時間各自保留，分別匯出字幕軌；即時字幕依各軌道時間從舊到新排列並自動捲動，時間戳僅於歷史記錄與匯出時顯示。不假造逐句配對或自動說話者辨識。即時手動標籤套用到接下來收到的原文，字幕可能延遲抵達，因此可在停止後修正。

背景知識只用來輔助語境與術語，不是保證採用的字典；翻譯仍可能有誤。JSON 匯出包含本次背景知識全文，但不包含 API key。

## 驗證狀態

本版測試及建置結果見 [BUILD_STATUS.md](BUILD_STATUS.md)。測試涵蓋兩家服務的本機 TLS 連線、繁中與數值保留、AI 段落位置、金鑰隔離、音源選擇，以及內容篩選事件後繼續／結束的不同生命週期。單元測試不代表已驗證 Teams 實機擷取或服務端誤判是否消失。

## 官方介面參考

- [GPT-Live WebSocket 指南](https://learn.microsoft.com/en-us/azure/foundry/openai/how-to/gpt-live)
- [GPT-Live 事件參考](https://learn.microsoft.com/en-us/azure/foundry/openai/gpt-live-reference)
- [GPT-Live 工作階段與認證範例](https://learn.microsoft.com/en-us/azure/foundry/openai/how-to/gpt-live-webrtc)
- [Microsoft Foundry 端點](https://learn.microsoft.com/en-us/azure/ai-studio/ai-services/concepts/endpoints)

GPT-Live 與傳統 GPT Realtime API 協定不同；不要將事件改成 `input_audio_buffer.append` 或 `response.audio.delta`。
