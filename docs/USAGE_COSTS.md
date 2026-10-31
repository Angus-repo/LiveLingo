# 費用統計（0.1.15）

新增「費用統計」頁籤，提供每場會議明細、今日／本週／本月摘要、日／週／月折線圖與各期總額。可翻頁檢視較早期間。USD 金額顯示四位小數，計算時不先逐筆四捨五入。

時間採 Asia/Taipei（UTC+8），每週週一至週日，月份為曆月。整場費用歸會議開始日期；跨午夜的會議不拆帳。每次開始至停止為一場，可在逐字稿中重新命名。

## 計量與持久化

- GPT-Live 使用 `session.usage.updated` 與 `session.closed` 的累計 `usage.seconds`；同一語音項目更新取代前值。服務最終回報優先；沒有最終回報時保留本機連線經過時間估算，且不低於已回報秒數。靜音仍計入。正常關閉與內容終止皆先收取用量。
- Realtime Translate／Live Transcribe／MAI 以成功送出的 PCM16 mono 24 kHz 音訊 bytes / 48,000 計秒，包含停止時補齊的短尾音，標示本機估算。原文轉錄另外計算，只有啟用時才計入。
- 文字翻譯與 AI 標點／分段擷取 Chat Completions `usage`，包含輸入、快取及輸出 token。輸出已含推理 token，不再重複加計。回應即使因 length、content_filter 或格式不合法未能採用，仍保存已回報用量。
- 每次文字請求先保存未確認項目；失敗、取消、逾時或缺少 usage 時保留未計價，不能視為免費。未啟用的功能不產生用量。測試連線不計入會議統計，供應商可能另收測試費用。
- 開始時快照費率，服務、資源與模型組合分別保存使用者費率。設定費率僅影響未來會議；明細中的「修訂本場費率」會明確重新計算該場。
- 費用獨立保存；刪除逐字稿不刪除費用。重啟、異常斷線與部分回應均保留最後完成寫入的資料。強制終止後尚未收到／保存的用量無法復原；未結算場次有提示，摘要與折線標示尚有未計價用量。
- 舊版逐字稿沒有用量資料，顯示「無用量資料」。未知費率、未知用量皆提示；小計只含已計價部分，折線以橘點標示資料不完整。

## 費率與限制

費用為本機估算，不是帳單，不含稅、合約折扣、其他裝置、區域加價、工具與特殊快取寫入費用。公開費率查核日 2026-10-31。Foundry GPT-Live、Foundry GPT-4.1 Mini 與無法辨識的自訂模型費率不自行猜測，請在「設定費率」填寫。Foundry 原文轉錄費率也需確認。快取價未知時按一般輸入價估算；已知快取價則只對該部分套用折扣。

目前 App 未設定 GPT-Live Responses delegation；若仍收到後端完成事件，保留為未計價，避免聲稱已包含全部費用。

來源：
- https://learn.microsoft.com/en-us/azure/foundry/openai/gpt-live-reference
- https://developers.openai.com/api/docs/pricing
- https://developers.openai.com/api/docs/guides/voice-latency-cost
- https://developers.openai.com/api/docs/models/gpt-5.4-mini
- https://developers.openai.com/api/docs/models/gpt-4.1-mini
- https://azure.microsoft.com/en-us/blog/gpt-6-astra-sol-and-luna-for-production-agents-in-microsoft-foundry/
- https://devblogs.microsoft.com/foundry/whats-new-in-microsoft-foundry-september-2026/
- https://techcommunity.microsoft.com/blog/azure-ai-foundry-blog/introducing-openai%E2%80%99s-gpt-5-4-mini-and-gpt-5-4-nano-for-low-latency-ai/4500569
- 其他語音模型來源見 `ModelPricing.kt`。

## 驗證

本版新增計價、累計去重、快取／推理 token、失敗與不完整回應、台灣日界／週界／月界、閏年與跨年、跨日歸帳、折線補零、費率快照、重啟／刪除保留、手動補價與儲存失敗測試。本機 core 76 與 shared 143 項測試全部通過，Android APK 建置成功；遠端 CI 狀態請以本次 PR 的 checks 為準。

尚需實機驗證窄螢幕、放大字體、長會議、真實帳單對帳與原簽章覆蓋安裝。iOS 以 CI 結果為準。
