# LiveLingo 0.1.2 驗證狀態

本版修正 Android WebSocket 握手後失敗，新增不收音的連線測試及安全診斷；維持 App 直接連接 Foundry。

## 已執行：2026-10-30

以 JDK 17、Gradle 8.14.3、Android SDK 36 執行：

```sh
./gradlew :core:jvmTest :shared:testDebugUnitTest :androidApp:assembleDebug
```

結果：**BUILD SUCCESSFUL，76 項測試全部通過，0 失敗、0 錯誤、0 略過。**

| 範圍 | 測試數 | 覆蓋內容 |
| --- | ---: | --- |
| Core | 27 | 端點、事件、PCM、語言、背景指示及字幕匯出 |
| App controller | 23 | 設定與資料保存、遷移、金鑰綁定、連線測試與取消、診斷遮蔽 |
| Shared transport | 13 | 啟動、傳送、停止排空、逾時、模型測試及錯誤隔離 |
| Diagnostics | 5 | HTTP／DNS／TLS 分類、安全代碼與識別碼、回應夾帶金鑰時的遮蔽 |
| Android native TLS | 8 | 真正 TLS＋OkHttp 的 101 握手及訊息交換；401／403／404／429／500、禁止轉址、過大或含金鑰的錯誤回應 |

原生握手測試使用 App 相同的 Android HTTP client 工廠及 Ktor 3.3.1／OkHttp 引擎，對本機測試伺服器建立 TLS WebSocket。這能檢查舊版不相容的 `maxFrameSize` 設定；並非真實 Foundry 憑證測試。

Android 開發測試 APK：`LiveLingo-0.1.2.apk`，12,429,791 bytes。

- Application ID：`app.livelingo.android`。
- Version name／code：`0.1.2`／`3`。
- Minimum／target SDK：`26`／`36`。
- `apksigner verify` 與 `zipalign` 檢查通過。
- 簽章憑證與 0.1.0／0.1.1 相同，可覆蓋安裝保留資料。
- 憑證 SHA-256：`cf232e1f105c3ac5a93ce2449b41678027b4b1ef9a54c3765c85b65a045d4bfb`。
- APK SHA-256：`f2932588d7b6934690658356ced52ff21f79f1ae9245410d5b55a810349d778f`。

上述 APK 是本次交付檔案；其他環境重新建置的雜湊可能不同。CI 若未設定 Android 說明所列的簽章 secret，runner 會使用另一把開發簽章。

## GitHub Actions

[Run 41064011698](https://github.com/Angus-repo/LiveLingo/actions/runs/41064011698) 的三個工作全部成功，驗證程式碼 commit：`191e78d7d72cb9bdabf620d613c42b0e116588f1`。

| 工作 | 結果 |
| --- | --- |
| shared-tests | 無 Android SDK 的 headless core 測試通過 |
| android | Android APK 建置、shared 單元測試及 APK artifact 上傳通過 |
| ios | Kotlin/Native framework 及未簽章 ARM64 模擬器 App 編譯通過 |

iOS 實際使用 Xcode 16.4（16F6）、iOS Simulator 18.5 SDK，Xcode 日誌回報 `BUILD SUCCEEDED`。

## 尚待驗證

沒有提供真實 Foundry 認證，尚未驗證雲端連線與翻譯品質。新版「測試連線」可在使用者裝置檢查實際資源、部署及金鑰；失敗報告不包含金鑰、音訊、背景知識或逐字稿。

尚未做 Android／iOS 實機麥克風、背景切換與長時間會話驗證。iPhone 簽章與實機安裝仍須使用自己的 Apple Developer Team。

iOS 專案宣告最低 16.0；此次 linker 提示 Skiko 內 ICU 資料物件以 iOS Simulator 17.2 編譯。模擬器編譯成功不代表已驗證 iOS 16 的執行相容性，發佈前需完成最低支援版本的實機驗證。
