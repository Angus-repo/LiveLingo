# Android 建置與簽章

以 JDK 17、Android SDK 36 執行：

```sh
./gradlew :androidApp:assembleDebug
```

輸出為 `androidApp/build/outputs/apk/debug/androidApp-debug.apk`。這是開發測試版，使用開發簽章；要以更新安裝保留資料，未來建置必須沿用同一把 keystore。GitHub Actions 支援選用的 repository secret `LIVELINGO_DEBUG_KEYSTORE_BASE64`：填入既有 debug keystore 的 Base64 內容後，CI 會在建置前還原至 runner 的私人目錄，讓後續 APK 沿用同一簽章。未設定此 secret 時，全新 runner 會產生另一把 debug key，無法保證覆蓋安裝。不要把 keystore 或 Base64 內容提交至原始碼。

正式發佈請自行保存一把專用 keystore，設定下列環境變數，勿提交 keystore 或密碼：

- `LIVELINGO_KEYSTORE_PATH`：keystore 絕對路徑。
- `LIVELINGO_KEYSTORE_PASSWORD`：keystore 密碼。
- `LIVELINGO_KEY_ALIAS`：金鑰別名。
- `LIVELINGO_KEY_PASSWORD`：金鑰密碼。

```sh
./gradlew :androidApp:assembleRelease
```

沒有簽章設定時，release 輸出為未簽署 APK。debug 與正式版均只允許 TLS；App 直接以 WSS 連線至設定的 Microsoft Foundry 資源，無需另架伺服器。Foundry API key 在手機輸入並以 Android Keystore 加密保存；HTTP/WebSocket 重新導向已停用。

錄音僅在 App 前景使用。離開 App、鎖定螢幕或系統中斷時會停止錄音並保存已收到的逐字稿，不會自動重啟麥克風。Android API 26 以上。
