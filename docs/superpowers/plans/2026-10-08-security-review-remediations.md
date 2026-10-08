# Security Review Remediations Implementation Plan

> **For agentic workers:** Execute each task inline in this session. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the wallet credit, role authorization, order product validation, and local upload path traversal issues found in the repository review.

**Architecture:** Disable simulated wallet top-ups by default and forbid them under the `prod` profile, while routing both legacy and current endpoints through one guarded service. Narrow Spring Security public matchers to actual authentication endpoints, validate every checkout product against the selected store and availability, and constrain local upload paths beneath the configured upload directory.

**Tech Stack:** Java 17, Spring Boot 3.4.3, Spring Security, Spring Data JPA, YAML configuration.

## Global Constraints

- Never create wallet balance from an unverified production request.
- Public authentication endpoints remain public; write APIs require the matching role.
- Product ownership and availability are checked on the server before order creation.
- Local uploads stay beneath the configured upload directory.
- Track Java compilation and focused regression-test results in `2026-10-08-full-review-remediations.md`.

---

### Task 1: Guard simulated wallet top-ups

**Files:**
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/resources/application-local.yml.example`
- Modify: `src/main/resources/application-prod.yml.example`
- Modify: `.env.prod.example`
- Modify: `src/main/java/com/example/demo/service/UserProfileService.java`
- Modify: `src/main/java/com/example/demo/controller/UserController.java`

**Interfaces:**
- Add `UserProfileService.topUpUser(Long userId, BigDecimal amount)`, returning the updated `User` after one guarded balance and ledger transaction.
- Keep `UserProfileService.topUp(Long userId, BigDecimal amount)` as the current API response wrapper around `topUpUser`.
- Both endpoints call the same guarded service path; mock top-up is disabled by default and always denied when the `prod` profile is active.

- [x] Add `app.wallet.mock-top-up.enabled: ${WALLET_MOCK_TOPUP_ENABLED:false}` to the main configuration; set it to `true` only in the local profile example and `false` in `.env.prod.example`.
- [x] Add a production-profile check and default-off property check before any top-up balance mutation.
- [x] Move balance/ledger mutation into `topUpUser`; route `/api/users/{userId}/recharge` through it instead of calling the generic transaction service directly.
- [x] Statically verify both exposed top-up routes reach the guard and no controller directly writes a `TOPUP` balance transaction.

### Task 2: Restrict authentication and brand write routes

**Files:**
- Modify: `src/main/java/com/example/demo/SecurityConfig.java`

**Interfaces:**
- Public POST routes: `/api/auth/register`, `/api/auth/login`, `/api/auth/social-login`, `/api/auth/corporate-login`, `/api/auth/firebase-verify`, `/api/auth/reset-password-firebase`, `/api/brand-auth/register`, and `/api/brand-auth/login`.
- Public GET route: `/api/auth/check-phone`.
- Keep `/api/auth/merge/**` public for the existing Firebase merge flow.
- Require `CUSTOMER` for `/api/auth/update`; require `BRAND` for the two `/api/brand-auth/create-store*` routes.

- [x] Remove broad public matchers for `/api/auth/**` and `/api/brand-auth/**`.
- [x] Add exact public matchers for the existing login, registration, verification, and merge routes.
- [x] Add role matchers for `/api/auth/update` and the two create-store routes before the final authenticated fallback.
- [x] Statically compare every current `UserController` and `BrandController` auth mapping with the public and role-protected matcher list.

### Task 3: Validate checkout products for the selected store

**Files:**
- Modify: `src/main/java/com/example/demo/service/OrderService.java`

**Interfaces:**
- Both `OrderService.repriceItems(Long storeId, List<OrderItem> items)` and the legacy `placeOrder` path reject products from another brand, globally disabled products, and products disabled for the selected store.
- Store-specific availability is read with the existing `StoreProductStatusRepository.findByStoreId(Long storeId)`; absent rows preserve the existing default of available.

- [x] Build one set of disabled product IDs per selected store using the existing repository method.
- [x] Reject the order when the store is inactive or has no brand.
- [x] For each fetched product in both order paths, require its brand ID to match the selected store's brand ID, `isEnabled` to be true, and its store-specific state not to be false before creating the order item or calculating its price.
- [x] Statically verify `/api/orders/checkout` continues to call `createOrder` and that `repriceItems` performs these checks before persisting or charging.

### Task 4: Keep local upload writes inside the upload root

**Files:**
- Modify: `src/main/java/com/example/demo/service/ImageStorageService.java`

**Interfaces:**
- Local uploads resolve the configured root and destination to normalized absolute paths.
- Reject any folder or filename resolution whose destination is outside that root; return a URL based on the validated relative path.

- [x] Normalize `localDir` to an absolute root and reject blank folder values.
- [x] Resolve and normalize the target directory and file; require both to remain beneath the upload root before creating or writing files.
- [x] Generate the returned `/uploads/...` URL from the validated root-relative path, using `/` separators.
- [x] Statically inspect all `ImageStorageService.upload` call sites and confirm path traversal cannot escape the configured root.

## Final Review

- [x] Review the diff for the four security findings and supporting configuration changes.
- [x] Confirm the repository's existing working-tree changes were preserved; see the full-project plan for verification results.
