# LiveLingo 0.1.3 驗證狀態

本版修正 Azure GPT-Live WebSocket 的 API key 認證，以 `api-key` 標頭取代先前的 Bearer 用法。維持 App 直接連接 Foundry、裝置安全儲存金鑰與禁止重新導向。

## 問題與修正依據

使用者在 0.1.2 回報 WebSocket 握手 HTTP 302。LiveKit 的 [Azure GPT-Live 實作](https://github.com/livekit/agents/blob/78c154d6edfff305b755ede26f42f11cf280bf4e/livekit-agents/livekit/agents/llm/_realtime/gpt_live.py#L580) 明確區分資源 API key（`api-key`）與 Entra token（`Authorization: Bearer`），並記錄 Bearer API key 會觸發帶 `?api-key=` 的轉址。

共用連線工廠現在只送出 `api-key`；Android 與共用 HTTP 診斷也從此標頭取得金鑰遮蔽依據。金鑰不放進 URL，不自動跟隨 302，不改變已儲存的端點、部署或金鑰。

此修正與使用者的 302 現象吻合；尚未使用其真實憑證驗證服務回應。

## 已執行：2026-10-30

以 JDK 17、Gradle 8.14.3、Android SDK 36 執行：

```sh
./gradlew :core:jvmTest :shared:testDebugUnitTest :androidApp:assembleDebug
```

結果：**BUILD SUCCESSFUL，77 項測試全部通過，0 失敗、0 錯誤、0 略過。**

| 範圍 | 測試數 | 覆蓋內容 |
| --- | ---: | --- |
| Core | 27 | 端點、事件、PCM、語言、背景指示及字幕匯出 |
| App controller | 23 | 設定與資料保存、遷移、金鑰綁定、連線測試與取消、診斷遮蔽 |
| Shared transport | 13 | 原始 API key 傳遞、無 URL／JSON 金鑰、啟停、排空與逾時 |
| Diagnostics | 5 | HTTP／DNS／TLS 分類、安全代碼與識別碼、回應夾帶金鑰時的遮蔽 |
| Android native TLS | 9 | 正式連線工廠的 api-key 認證；101 握手、401／403／404／429／500、禁止轉址及錯誤回應遮蔽 |

新增原生測試直接呼叫 App 正式使用的 `KtorFoundrySocketFactory`。本機 TLS 伺服器模擬 Bearer 認證回傳 302、api-key 認證接受 WebSocket；驗證沒有 `Authorization`、沒有 URL query、只有一次 HTTP 請求且可交換訊息。它不是使用者 Azure 資源的連線測試。

Android 開發測試 APK：`LiveLingo-0.1.3.apk`，12,637,813 bytes。

- Application ID：`app.livelingo.android`。
- Version name／code：`0.1.3`／`4`。
- Minimum／target SDK：`26`／`36`。
- `apksigner verify` 與 `zipalign` 檢查通過。
- 簽章憑證與 0.1.0／0.1.1／0.1.2 相同，可覆蓋安裝保留資料。
- 憑證 SHA-256：`cf232e1f105c3ac5a93ce2449b41678027b4b1ef9a54c3765c85b65a045d4bfb`。
- APK SHA-256：`5b603f880dc14561fc81ec13ad4fa4340cd64f42a824f44957fdb529c664cebf`。
- 已直接檢查打包 DEX，確認包含 0.1.3 版本文字及「認證方式：api-key 標頭」。

上述 APK 是本次交付檔案；其他環境重新建置的雜湊可能不同。CI 若未設定 Android 說明所列的簽章 secret，runner 會使用另一把開發簽章。

## GitHub Actions

0.1.3 的共用測試、Android 與 iOS 工作待本次提交觸發及確認。此處尚未將前版 iOS 成功結果當成本版驗證。

## 尚待驗證

沒有提供真實 Foundry 認證，尚未驗證雲端連線與翻譯品質。新版「測試連線」可在使用者裝置檢查實際資源、部署及金鑰；失敗報告不包含金鑰、音訊、背景知識或逐字稿。

尚未做 Android／iOS 實機麥克風、背景切換與長時間會話驗證。iPhone 簽章與實機安裝仍須使用自己的 Apple Developer Team。

iOS 專案宣告最低 16.0；前版 linker 提示 Skiko 內 ICU 資料物件以 iOS Simulator 17.2 編譯。模擬器編譯成功不代表已驗證 iOS 16 的執行相容性，發佈前需完成最低支援版本的實機驗證。
