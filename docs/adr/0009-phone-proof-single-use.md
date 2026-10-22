# ADR-0009：手機驗證權杖只能成功使用一次

- **狀態**：Accepted（已知缺口：社群權杖未納入，見「後果」）
- **日期**：2026-10-15
- **相關**：ADR-0004（限流與 IP 信任）、ADR-0006（Redis 與本地退避）

## 背景

重設密碼、設定密碼、綁定第三方、建立帳號，都要靠 Firebase 手機驗證的 ID token 證明「持有這個號碼」。這類 token 在到期（1 小時）之前，可以被 `verifyIdToken` 無限次通過。

原本 `verifyFirebasePhone` 只比對號碼。因此只要一張 token 洩漏，攻擊者在一小時內都可以拿它反覆重設同一帳號的密碼，或在之後綁定第三方帳號。

社群登入權杖（Google、Facebook）的情況不同：整合流程會在多個步驟中重複使用同一張社群 token（先設定密碼、再綁定），所以不能套用同一條規則。

## 決策

1. 通過號碼比對、準備執行動作時，記錄手機驗證權杖的 SHA-256 指紋（`FirebaseTokenReplayGuard.claimOnce`）。同一張在保留期間內再次使用，回 401「此手機驗證已使用，請重新取得驗證碼」。
2. 保留 2 小時，長於 ID token 的 1 小時有效期加上時鐘誤差。超過之後 `verifyIdToken` 本身就會拒絕，記錄不再需要。
3. 只保存指紋：Redis 鍵為 `fbtoken:<sha256>`。權杖本身不寫入 Redis，也不寫入記錄檔。
4. 記錄放在號碼比對之後，所以號碼不符的嘗試不會燒掉權杖。
5. 預先檢查（`verifyOnly`，即前端註冊頁的 `firebase-verify`）不記錄。否則預先檢查會讓真正的請求失效。
6. `MOCK_TOKEN` 不記錄。它是本機模擬用的固定字串，只在非 prod 且 `sms.mode=mock` 時有效。
7. 記錄分兩層（`ClaimStore`）：一律先寫本地，同一實例內恆有效；有 Redis 時再以 `SET NX` 寫入 Redis，讓多實例共享。Redis 拋例外時退回本地並寫警告。

## 後果

取得的：

- 同一張手機驗證權杖只能成功使用一次。重放會被擋下，包括多實例之間的重放，以及 Redis 故障後恢復時的重放。
- 規則集中在 `ClaimStore`（純邏輯，有單元測試）與 `FirebaseTokenReplayGuard`。`AuthService` 只在 `verifyFirebasePhone` 一處呼叫，之後新增的手機驗證端點只要經過這裡，就會自動受限。

付出的：

- 通過號碼比對之後的失敗仍會燒掉權杖。例如第三方帳號與權杖不符、或號碼已被註冊，使用者都必須重新取得驗證碼。這是刻意的取捨：若要在交易失敗時釋放記錄，釋放本身會留下競態窗口。
- Redis 故障期間只保證單一實例內正確，多實例之間不共享紀錄。這與 ADR-0004 的限流採用相同的退避策略。
- 社群權杖（Google、Facebook）未納入。整合流程會在多個步驟中重複使用同一張社群 token。要納入，需要先把多步驟流程改為單一請求，或改用伺服器端發出的整合憑證。這是產品流程的決定，目前沒有做。
- 「登出所有裝置」（stage 8，2026-10-22）只撤銷權杖版本，與手機驗證權杖的單次使用互不影響；它會切斷處理該請求的實例上既有的 STOMP 連線（`TokenRevocationCutListener`）。

## 替代方案

- **Firebase 的 `checkRevoked`**：它只比對使用者層級的撤銷時間（`revokeRefreshTokens`），無法區分同一使用者的兩張 token，因此不能用來做單次使用。
- **記錄放進資料庫交易**：可以隨交易回滾，但多實例下需要唯一約束，且資料庫故障時整個手機驗證會停擺。目前的選擇與 ADR-0006 的 Redis 共享狀態一致。
- **只縮短 token 有效期**：Firebase 的有效期不由本專案控制。

## 如何驗證

- 純邏輯：`ClaimStoreTest`（7）、`FirebaseTokenReplayGuardTest`（5）、`TokenFingerprintTest`（5）、`LocalClaimStoreTest`（4）。
- 接線（文字掃描）：`PhoneTokenSingleUseGuardTest`（2）確認記錄只出現在 `verifyFirebasePhone`，`verifyOnly` 不得記錄。
- 行為（需要 Mockito，CI 執行）：`AuthServiceSecurityTest.verifiedPhoneTokenCanOnlyBeUsedOnce` 與 `preCheckDoesNotConsumeThePhoneToken`。
- 還原驗證：見 README「2026-10-15 還原驗證」的 R 系列與 W 系列。
