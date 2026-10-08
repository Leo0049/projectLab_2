# Full-Project Review Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Repair the confirmed authentication, group-order authorization and availability, quantity/pricing, topping-rule, tenant-isolation, XSS, account-deletion, cart-summary, and public-data exposure findings.

**Architecture:** Keep authorization and price decisions on the server. Reuse Firebase token verification and the existing pricing service; constrain numeric group-order routes to the owner or a known participant; encode untrusted storefront data before DOM insertion. Preserve the repository's current API and DTO style where possible.

**Tech Stack:** Java 17, Spring Boot 3.4, Spring Security, JPA, Firebase Admin, JUnit 5/Mockito, browser JavaScript.

## Global Constraints

- Preserve pre-existing working-tree changes; do not reset or overwrite them.
- Do not use OCR or external code-review services.
- Reject identity, quantity, price-option, and tenant identifiers that are not proven by server-side state.
- Keep local mock Firebase behavior available only when `sms.mode=mock`.

---

### Task 1: Secure account merges and deleted-user authentication

**Files:**
- Modify: `src/main/java/com/example/demo/service/AuthService.java`
- Modify: `src/main/java/com/example/demo/controller/UserController.java`
- Modify: `src/main/java/com/example/demo/common/JwtAuthenticationFilter.java`
- Modify: `src/main/java/com/example/demo/SecurityConfig.java`
- Modify: `frontend/Customer/auth/account-verify.html`
- Modify: `frontend/Customer/auth/js/verify-phone.js`
- Test: authentication-focused tests under `src/test/java/com/example/demo/`

- [x] Validate phone-auth tokens by exact normalized phone match in merge-password and password-reset paths; allow the mock token only in mock mode.
- [x] Require proof of ownership for both identities during social linking: an existing customer JWT for password-confirmed links or a verified phone-auth token for OTP links, plus a Firebase social token whose UID equals `providerUid`.
- [x] Route the existing phone-only login merge case through the verified-phone password-reset flow instead of treating absent social UID data as a social link.
- [x] Reject deleted users in social login and reject their existing JWTs in the authentication filter.
- [x] Add regression coverage for mismatched phone, mismatched provider UID, missing proof, and deleted-user login.

### Task 2: Authorize numeric group-order endpoints

**Files:**
- Modify: `src/main/java/com/example/demo/controller/GroupOrderController.java`
- Modify: `src/main/java/com/example/demo/service/GroupOrderService.java`
- Test: `src/test/java/com/example/demo/controller/GroupOrderSecurityTest.java`

- [x] Require the owner or an existing member for member-list and payment-summary reads; limit share-token retrieval and order-ID DTO lookup to the owner.
- [x] Require the matching share token for legacy numeric-ID joins, and reject products from another brand/store.
- [x] Use the same quantity and pricing validation as the token-based group-item flow.
- [x] Enforce active-store, global-product, and store-specific availability checks during group creation, item migration/addition, submit, and checkout.
- [x] Test guessed IDs, non-member reads, owner reads, invalid invite tokens, and foreign-brand products.

### Task 3: Validate quantities and calculate canonical cart/order prices

**Files:**
- Modify: `src/main/java/com/example/demo/service/PricingService.java`
- Modify: `src/main/java/com/example/demo/service/CartService.java`
- Modify: `src/main/java/com/example/demo/service/GroupOrderService.java`
- Modify: `src/main/java/com/example/demo/service/OrderService.java`
- Test: focused pricing/cart/order tests under `src/test/java/com/example/demo/service/`

- [x] Enforce quantity range 1–99 in cart creation/update, cart migration, and legacy group joins.
- [x] Resolve size price from the product's server-side SIZE relation, then add regional and validated topping charges.
- [x] Recompute cart unit/final price when size or toppings change; multiply unit price by quantity in cart totals.
- [x] Reprice legacy order requests on the server and persist topping snapshots with their server-derived prices.
- [x] Enforce product-specific topping rules, duplicate rejection, and maximum topping counts across cart, group, and order flows.
- [x] Test negative/zero/oversized quantities, size-price selection, topping totals, and quantity-aware summaries.

### Task 4: Enforce brand ownership and safe customer rendering

**Files:**
- Modify: `src/main/java/com/example/demo/service/BrandService.java`
- Modify: `frontend/Customer/store.html`
- Modify: `src/main/java/com/example/demo/controller/DailySpinController.java`
- Test: brand catalog and rendering-focused tests where supported

- [x] Verify category, spec, and topping IDs belong to the authenticated brand before saving product relations.
- [x] Escape product/category text and attributes and use generated category anchors in the customer storefront.
- [x] Return only public brand fields from the public game-wheel brands endpoint.
- [x] Test foreign-brand IDs are rejected and confirm frontend scripts parse successfully.

### Task 5: Run checks and report remaining limitations

- [x] Check Maven availability: neither `mvn` nor `mvnw`/`mvnw.cmd` is available (only `.mvn/wrapper/maven-wrapper.properties` exists). Compiled all production and test Java sources with the cached JDK/dependencies and ran 22 focused JUnit tests; all passed.
- [x] Run JavaScript syntax checks for modified standalone and inline scripts (all passed) and inspect `git -c core.whitespace=cr-at-eol diff --check` (passed).
- [x] Review the final diff and working-tree status; earlier working-tree changes were preserved.
