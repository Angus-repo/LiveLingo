# LiveLingo 0.1.6 驗證狀態

2026-10-31（台灣時間），以 JDK 17、Gradle 8.14.3、Android SDK 36 建置及測試。

```sh
./gradlew :core:jvmTest :shared:testDebugUnitTest :androidApp:assembleDebug
```

**BUILD SUCCESSFUL；106 項測試，0 失敗、0 錯誤、0 略過。**

| 範圍 | 數量 | 重點 |
| --- | ---: | --- |
| Core | 36 | 繁中台灣字詞、畫面回報案例、數值保留、端點限制、GPT-Live 協定、字幕匯出 |
| Controller | 31 | 舊設定載入、兩家服務金鑰隔離、重開還原、執行中不可切換、停止後 AI 分段保存、分段失敗保留字幕 |
| Live display | 6 | 片段正序、單字接合、保留換行、語言推定 |
| Paragraphs | 4 | AI 僅回傳編號、拒絕無效位置與改寫、限長、保留早期段落 |
| Transport | 13 | 啟動、PCM 傳遞、停止排空、斷線、逾時 |
| Diagnostics | 5 | 安全錯誤欄位、金鑰遮蔽 |
| Native TLS WebSocket | 10 | 正式 OkHttp 工廠、Foundry api-key 與 OpenAI Bearer、禁止重新導向 |
| Native TLS paragraph HTTP | 1 | 在同一測試逐一驗證兩家 API 的網址、認證、JSON 請求／回應、禁止轉址及拒絕無效 AI 編號 |

## 交付 APK

- 檔名：`LiveLingo-0.1.6-original-key.apk`
- Application ID：`app.livelingo.android`
- Version name／code：`0.1.6`／`7`
- Minimum／target SDK：`26`／`36`
- 大小：13,226,851 bytes
- APK SHA-256：`7dc72ed3bb91997acbecc0ce9d24035370f07f445cb0b822390b402a17181b04`
- 簽章憑證 SHA-256：`cf232e1f105c3ac5a93ce2449b41678027b4b1ef9a54c3765c85b65a045d4bfb`
- `apksigner verify` 與 `zipalign -c -P 16 4` 通過，與原始 0.1.x 簽章一致，可覆蓋安裝。
- APK 內含 OpenCC 資料授權。

## 使用條件與驗證界線

AI 語意分段需設定可用的文字模型：Foundry 在同一資源另外部署文字模型並填入部署名稱；OpenAI 預設 `gpt-4.1-mini`。未設定或 API 失敗時，畫面顯示基本分段狀態。基本分段不是 AI 語意判斷。

繁中轉換在裝置端完成，原始 provider fragments 不改寫，JSON 保留原始片段與 AI 分段位置；SRT／VTT／TXT 依目標語言呈現繁中，時間戳保留。

沒有使用真實 Foundry／OpenAI API key 進行雲端測試。上述 TLS 測試使用本機伺服器，驗證正式網路元件與協定，不代表已驗證帳號權限、實際模型翻譯品質或分段品質。

本次未執行 Android 實機／模擬器的 UI、長時間背景收音測試，亦未在本機執行 iOS 編譯。共用程式與 iOS 版號已同步；iOS 仍須以 macOS CI 與實機驗收。原有 Android 前景服務、通知停止及無時間戳即時字幕流程保留。
