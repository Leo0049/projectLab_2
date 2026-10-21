package com.example.demo.service;

import com.example.demo.common.AccountRef;
import com.example.demo.common.AccountsSuspendedEvent;
import com.example.demo.common.BrandApproval;
import com.example.demo.common.JwtUtils;
import com.example.demo.entity.AdminAccount;
import com.example.demo.entity.Brand;
import com.example.demo.exception.CustomException;
import com.example.demo.repository.AdminAccountRepository;
import com.example.demo.repository.BrandRepository;
import com.example.demo.repository.StoreRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 管理員登入與品牌審核（N-28）。 */
@ExtendWith(MockitoExtension.class)
class AdminServiceTest {

    @Mock
    private AdminAccountRepository admins;
    @Mock
    private BrandRepository brands;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtUtils jwtUtils;
    @Mock
    private StoreRepository stores;
    @Mock
    private ApplicationEventPublisher events;
    @InjectMocks
    private AdminService service;

    private static Brand brandWith(String approvalStatus, boolean suspended) {
        Brand brand = new Brand();
        brand.setId(5L);
        brand.setName("茶");
        brand.setApprovalStatus(approvalStatus);
        brand.setSuspended(suspended);
        return brand;
    }

    @Test
    @DisplayName("停用已核准的品牌：品牌與其分店的版本一併遞增，並發出切斷事件（stage 6）")
    void suspendingABrandBumpsItsStoresAndPublishesTheCut() {
        when(stores.findIdsByBrandId(5L)).thenReturn(List.of(31L, 32L));
        when(brands.suspendBrand(eq(5L), eq(BrandApproval.APPROVED), any())).thenReturn(1);

        Map<String, Object> out = service.suspendBrand(1L, 5L);

        assertEquals(Boolean.TRUE, out.get("suspended"));
        assertEquals(2, out.get("affectedStores"));
        verify(stores).bumpTokenVersionsOfBrand(5L);
        ArgumentCaptor<AccountsSuspendedEvent> event = ArgumentCaptor.forClass(AccountsSuspendedEvent.class);
        verify(events).publishEvent(event.capture());
        assertEquals(List.of(new AccountRef("BRAND", 5L), new AccountRef("STORE", 31L), new AccountRef("STORE", 32L)),
                event.getValue().accounts());
    }

    @Test
    @DisplayName("只能停用已核准的品牌：待審品牌回 409，不遞增分店版本、不發出事件（stage 6）")
    void pendingBrandCannotBeSuspended() {
        when(brands.suspendBrand(eq(5L), eq(BrandApproval.APPROVED), any())).thenReturn(0);
        when(brands.findById(5L)).thenReturn(Optional.of(brandWith(BrandApproval.PENDING, false)));

        CustomException e = assertThrows(CustomException.class, () -> service.suspendBrand(1L, 5L));

        assertEquals("409", e.getCode());
        verify(stores, never()).bumpTokenVersionsOfBrand(anyLong());
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("已停用的品牌再次停用回 409；不存在的品牌回 404（stage 6）")
    void alreadySuspendedIsA409AndMissingIsA404() {
        when(brands.suspendBrand(eq(5L), eq(BrandApproval.APPROVED), any())).thenReturn(0);
        when(brands.findById(5L)).thenReturn(Optional.of(brandWith(BrandApproval.APPROVED, true)));
        when(brands.suspendBrand(eq(9L), eq(BrandApproval.APPROVED), any())).thenReturn(0);
        when(brands.findById(9L)).thenReturn(Optional.empty());

        assertEquals("409", assertThrows(CustomException.class, () -> service.suspendBrand(1L, 5L)).getCode());
        assertEquals("404", assertThrows(CustomException.class, () -> service.suspendBrand(1L, 9L)).getCode());
    }

    @Test
    @DisplayName("恢復品牌不遞增版本、不發出事件；未停用回 409，不存在回 404（stage 6）")
    void reinstatingDoesNotBumpVersionsAndChecksState() {
        when(brands.reinstateBrand(5L)).thenReturn(1);

        Map<String, Object> out = service.reinstateBrand(1L, 5L);

        assertEquals(Boolean.FALSE, out.get("suspended"));
        verify(stores, never()).bumpTokenVersionsOfBrand(anyLong());
        verifyNoInteractions(events);

        when(brands.reinstateBrand(6L)).thenReturn(0);
        when(brands.existsById(6L)).thenReturn(true);
        when(brands.reinstateBrand(7L)).thenReturn(0);
        when(brands.existsById(7L)).thenReturn(false);
        assertEquals("409", assertThrows(CustomException.class, () -> service.reinstateBrand(1L, 6L)).getCode());
        assertEquals("404", assertThrows(CustomException.class, () -> service.reinstateBrand(1L, 7L)).getCode());
    }

    @Test
    @DisplayName("停用單一分店只切斷該分店的連線，不動品牌與其他分店（stage 6）")
    void suspendingAStoreCutsOnlyThatStore() {
        when(stores.suspendStore(eq(31L), any())).thenReturn(1);

        Map<String, Object> out = service.suspendStore(1L, 31L);

        assertEquals(Boolean.TRUE, out.get("suspended"));
        ArgumentCaptor<AccountsSuspendedEvent> event = ArgumentCaptor.forClass(AccountsSuspendedEvent.class);
        verify(events).publishEvent(event.capture());
        assertEquals(List.of(new AccountRef("STORE", 31L)), event.getValue().accounts());
        verify(stores, never()).bumpTokenVersionsOfBrand(anyLong());
    }

    @Test
    @DisplayName("分店已停用回 409，不存在回 404；恢復未停用的分店回 409（stage 6）")
    void storeStateErrorsAreDistinguished() {
        when(stores.suspendStore(eq(31L), any())).thenReturn(0);
        when(stores.existsById(31L)).thenReturn(true);
        when(stores.suspendStore(eq(32L), any())).thenReturn(0);
        when(stores.existsById(32L)).thenReturn(false);
        when(stores.reinstateStore(31L)).thenReturn(0);

        assertEquals("409", assertThrows(CustomException.class, () -> service.suspendStore(1L, 31L)).getCode());
        assertEquals("404", assertThrows(CustomException.class, () -> service.suspendStore(1L, 32L)).getCode());
        assertEquals("409", assertThrows(CustomException.class, () -> service.reinstateStore(1L, 31L)).getCode());
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("已核准品牌清單與分店清單的頁碼、筆數有上下限；品牌不存在回 404（stage 6）")
    void suspensionListsAreClampedAndMissingBrandIs404() {
        when(brands.findByApprovalStatus(eq(BrandApproval.APPROVED), any(Pageable.class))).thenReturn(Page.empty());
        Map<String, Object> approved = service.listApprovedBrands(-1, 0);
        assertEquals(0, approved.get("page"));
        assertEquals(1, approved.get("size"));

        when(brands.findById(5L)).thenReturn(Optional.of(brandWith(BrandApproval.APPROVED, false)));
        when(stores.findPageByBrandId(eq(5L), any(Pageable.class))).thenReturn(Page.empty());
        Map<String, Object> listed = service.listStoresOfBrand(5L, -3, 10_000);
        assertEquals(0, listed.get("page"));
        assertEquals(100, listed.get("size"));

        when(brands.findById(9L)).thenReturn(Optional.empty());
        assertEquals("404", assertThrows(CustomException.class, () -> service.listStoresOfBrand(9L, 0, 10)).getCode());
    }

    private static AdminAccount admin(long id, String hash, int tokenVersion) {
        AdminAccount admin = new AdminAccount();
        admin.setId(id);
        admin.setUsername("ops");
        admin.setPasswordHash(hash);
        admin.setTokenVersion(tokenVersion);
        return admin;
    }

    @Test
    @DisplayName("正確的帳號密碼取得 ADMIN 權杖，權杖帶著帳號目前的版本")
    void correctPasswordIssuesAnAdminToken() {
        when(admins.findByUsername("ops")).thenReturn(Optional.of(admin(5L, "hash", 2)));
        when(passwordEncoder.matches("pw", "hash")).thenReturn(true);
        when(jwtUtils.generateToken(5L, "ADMIN", "ops", 2)).thenReturn("admin-token");

        Map<String, Object> data = service.login("ops", "pw");

        assertEquals("admin-token", data.get("token"));
        assertEquals("ADMIN", data.get("role"));
    }

    @Test
    @DisplayName("密碼錯誤與帳號不存在回同一則訊息，避免列舉管理員帳號")
    void wrongPasswordAndUnknownUserLookTheSame() {
        when(admins.findByUsername("ops")).thenReturn(Optional.of(admin(5L, "hash", 0)));
        when(passwordEncoder.matches("bad", "hash")).thenReturn(false);
        CustomException wrongPassword = assertThrows(CustomException.class, () -> service.login("ops", "bad"));

        when(admins.findByUsername("ghost")).thenReturn(Optional.empty());
        CustomException unknownUser = assertThrows(CustomException.class, () -> service.login("ghost", "pw"));

        assertEquals("401", wrongPassword.getCode());
        assertEquals("401", unknownUser.getCode());
        assertEquals(wrongPassword.getMessage(), unknownUser.getMessage());
    }

    @Test
    @DisplayName("核准待審品牌：條件式更新只改一次，回傳核准後的狀態")
    void approvePendingBrandTransitionsOnce() {
        when(brands.transitionApprovalStatus(eq(9L), eq("PENDING"), eq("APPROVED"), any())).thenReturn(1);

        Map<String, Object> data = service.approveBrand(1L, 9L);

        assertEquals("APPROVED", data.get("approvalStatus"));
        assertEquals(9L, data.get("brandId"));
    }

    @Test
    @DisplayName("退件待審品牌")
    void rejectPendingBrand() {
        when(brands.transitionApprovalStatus(eq(9L), eq("PENDING"), eq("REJECTED"), any())).thenReturn(1);

        assertEquals("REJECTED", service.rejectBrand(1L, 9L).get("approvalStatus"));
    }

    @Test
    @DisplayName("已處理過的品牌不可再核准或退件（回 409）")
    void alreadyDecidedBrandIsAConflict() {
        when(brands.transitionApprovalStatus(eq(9L), eq("PENDING"), eq("APPROVED"), any())).thenReturn(0);
        when(brands.existsById(9L)).thenReturn(true);

        CustomException e = assertThrows(CustomException.class, () -> service.approveBrand(1L, 9L));

        assertEquals("409", e.getCode());
    }

    @Test
    @DisplayName("品牌不存在回 404")
    void missingBrandIsNotFound() {
        when(brands.transitionApprovalStatus(eq(9L), eq("PENDING"), eq("REJECTED"), any())).thenReturn(0);
        when(brands.existsById(9L)).thenReturn(false);

        CustomException e = assertThrows(CustomException.class, () -> service.rejectBrand(1L, 9L));

        assertEquals("404", e.getCode());
    }

    @Test
    @DisplayName("待審清單的頁碼與筆數有上下限：負數頁碼視為第一頁，筆數上限 100")
    void pendingListClampsPageAndSize() {
        when(brands.findByApprovalStatus(eq("PENDING"), any(Pageable.class))).thenReturn(Page.empty());

        Map<String, Object> data = service.listPendingBrands(-3, 500);

        ArgumentCaptor<Pageable> requested = ArgumentCaptor.forClass(Pageable.class);
        verify(brands).findByApprovalStatus(eq("PENDING"), requested.capture());
        assertEquals(0, requested.getValue().getPageNumber());
        assertEquals(AdminService.MAX_PAGE_SIZE, requested.getValue().getPageSize());
        assertEquals(0L, data.get("total"));
        assertFalse((Boolean) data.get("hasNext"));
    }
}
