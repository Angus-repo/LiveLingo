# 即時譯 LiveLingo

使用 Kotlin Multiplatform + Compose Multiplatform 的 Android／iOS 即時字幕翻譯 App。**0.1.1 起由 App 直接連接 Microsoft Foundry GPT-Live，不需自架後端。**

API key 由使用者自行輸入，Android 以 Keystore 保護加密金鑰並加密保存，iOS 以 Keychain 保存。原始碼、APK、逐字稿匯出及一般設定均不內建或保存明文 API key。

## 功能

| 功能 | 說明 |
| --- | --- |
| 直接連線 | 設定 Foundry 資源端點、GPT-Live 部署名稱與 API key |
| 連線診斷 | 不開啟麥克風測試連線，顯示錯誤階段與可取得的 HTTP 狀態碼 |
| 來源語言 | 自動、英文、印度英文、繁體中文、日文、韓文 |
| 目標語言 | 英文、印度英文、繁體中文、日文、韓文 |
| 背景知識 | 管理多組場景與術語，翻譯前選擇一組或不使用 |
| 即時字幕 | 原文與譯文分別顯示，保留各自時間戳 |
| 逐字稿 | 自動保存在裝置，支援歷史記錄、重新命名與刪除 |
| 匯出 | SRT、WebVTT、TXT、JSON |
| 發言者 | 手動標記接下來收到的原文，或於停止後修正 |

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
4. 選擇來源、目標語言與本次背景知識，開始翻譯並允許麥克風。
5. 停止後可到「逐字稿」檢視、修正發言者與匯出字幕。

不需要服務網址、relay token、Docker、Java 後端或另外申請伺服器。App 會將 HTTPS 資源端點轉成對應的 WSS GPT-Live 工作階段路徑。不要填入 Foundry Portal 網頁、專案管理網址或舊版 `/realtime` API。

API key 儲存後不會重新顯示。只修改部署名稱時，API key 留空可保留原值；變更資源端點時必須重新輸入對應 key，避免將舊 key 傳給另一個資源。若按「移除 API key」，再次翻譯前須重新輸入。

## 0.1.2 連線修正

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

- App 以 WSS 直接連接 Microsoft Foundry，沒有中繼後端。使用 `Authorization: Bearer` 傳送使用者的資源 API key，不把 key 放在 URL。
- 錄音為 24 kHz、單聲道、PCM16 little-endian。iOS 會從裝置實際取樣率轉換。
- 收到 `session.started` 後才開始收音；停止時送出最後已擷取的音訊與 `session.close`，等候最後字幕及 `session.closed`。
- 只在前景收音。來電、離開 App、斷線或錯誤會停止；不會自動恢復錄音或重播先前音訊。
- 未收到任何服務事件達 90 秒時視為連線異常並停止；這是 App 的逾時策略，並非模型的回覆時限保證。
- 不保存原始錄音檔。音訊與選定背景知識會直接傳至 Microsoft Foundry。
- 逐字稿與背景知識保存在 App 專用空間；API key 使用系統保護的安全儲存區，與一般資料分開。
- 停用重新導向，避免認證資訊跟隨轉址；App 只接受受支援的 Azure HTTPS 資源端點。

## 字幕與發言者限制

GPT-Live 回傳的是帶時間範圍的串流片段，沒有每個字的精準對齊或多人 `speaker_id`。App 合併相鄰片段方便閱讀，原始片段仍保存於 JSON。

原文與譯文時間各自保留，分別匯出字幕軌；不假造逐句配對或自動說話者辨識。即時手動標籤套用到接下來收到的原文，字幕可能延遲抵達，因此可在停止後修正。

背景知識只用來輔助語境與術語，不是保證採用的字典；翻譯仍可能有誤。JSON 匯出包含本次背景知識全文，但不包含 API key。

## 驗證狀態

0.1.2 的 76 項自動化測試通過，其中 8 項使用實際 TLS 與 Android 所用的 OkHttp 引擎，驗證 WebSocket 握手、錯誤碼及金鑰遮蔽。GitHub Actions 的共用測試、Android APK 與 iOS ARM64 模擬器 App 編譯皆通過。實機與真實 Foundry 連線尚待驗證。

實際執行結果見 [BUILD_STATUS.md](BUILD_STATUS.md)，實機驗收步驟見 [docs/ACCEPTANCE.md](docs/ACCEPTANCE.md)。沒有真實 Foundry 憑證時，離線協定與流程測試不能取代模型連線及翻譯品質驗證。

## 官方介面參考

- [GPT-Live WebSocket 指南](https://learn.microsoft.com/en-us/azure/foundry/openai/how-to/gpt-live)
- [GPT-Live 事件參考](https://learn.microsoft.com/en-us/azure/foundry/openai/gpt-live-reference)
- [GPT-Live 工作階段與認證範例](https://learn.microsoft.com/en-us/azure/foundry/openai/how-to/gpt-live-webrtc)
- [Microsoft Foundry 端點](https://learn.microsoft.com/en-us/azure/ai-studio/ai-services/concepts/endpoints)

GPT-Live 與傳統 GPT Realtime API 協定不同；不要將事件改成 `input_audio_buffer.append` 或 `response.audio.delta`。
