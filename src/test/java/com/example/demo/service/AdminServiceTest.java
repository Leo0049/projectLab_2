package com.example.demo.service;

import com.example.demo.common.JwtUtils;
import com.example.demo.entity.AdminAccount;
import com.example.demo.exception.CustomException;
import com.example.demo.repository.AdminAccountRepository;
import com.example.demo.repository.BrandRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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
    @InjectMocks
    private AdminService service;

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
