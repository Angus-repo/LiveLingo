# iOS 建置與直連設定

1. 在 Mac 安裝 JDK 17、Xcode 26.0（本專案 Kotlin 2.2.21 的相容版本）與 iOS SDK。
2. 開啟 `iosApp/LiveLingo.xcodeproj`，選擇 `LiveLingo` Scheme。模擬器目前支援 ARM64。
3. 實體 iPhone 請在 Signing & Capabilities 選擇自己的 Apple Developer Team 與可用 Bundle Identifier。
4. 在 App「設定」直接填入 Foundry HTTPS 資源端點、`gpt-live-1` 部署名稱及 API key，不需要自架後端。
5. 選擇翻譯語言／背景知識，開始時允許麥克風權限。

Xcode 的 Compile Kotlin Framework 階段會呼叫 `:shared:embedAndSignAppleFrameworkForXcode`，無需 CocoaPods 或 XcodeGen。第一次建置需下載 Gradle、Kotlin/Native、Compose 及 Ktor 相依套件。同一 Gradle 專案包含 Android targets，依 CI 範例備妥 Android SDK 可避免配置階段缺少 SDK。

```sh
xcodebuild -project iosApp/LiveLingo.xcodeproj \
  -scheme LiveLingo -configuration Debug \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
  ARCHS=arm64 ONLY_ACTIVE_ARCH=NO CODE_SIGNING_ALLOWED=NO build
```

## 原生實作

- AVAudioEngine 讀取裝置實際音訊格式，經 AVAudioConverter 轉為 24 kHz、單聲道、PCM16 little-endian。
- 每 100 ms 傳送一包；停止時排空轉換器與最後不足一包的音訊。
- 處理麥克風拒絕權限、來電、音訊裝置切換與進入背景，不會自動恢復錄音。
- API key 保存於 Keychain，限裝置解鎖讀取、不跟隨備份轉移；與一般設定、逐字稿分開。
- Keychain service 名稱保留舊識別字以相容 0.1.0，並不代表還有中繼服務。
- 一般資料以原子寫入保存在 App 專用目錄，匯出時使用 iOS 系統分享面板，含 iPad popover。
- 僅使用 TLS，停用 HTTP 重新導向與舊版區域網路例外。

## 尚待驗證

本次開發環境沒有 Xcode，未完成 iOS 編譯、簽章或實機錄音測試。GitHub Actions 包含 iOS 建置流程，但請以實際執行結果為準。

請於實機驗證權限拒絕／允許、48 kHz 輸入轉換、藍牙耳機切換、來電、進背景、停止後重開、Keychain 保存、直接連 Foundry 以及 iPad 匯出。上架前依實際資料處理方式核對 PrivacyInfo.xcprivacy 與 App Store 隱私說明。
