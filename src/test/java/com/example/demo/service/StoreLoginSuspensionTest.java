package com.example.demo.service;

import com.example.demo.common.JwtUtils;
import com.example.demo.entity.Store;
import com.example.demo.exception.CustomException;
import com.example.demo.repository.StoreRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 分店登入的停用檢查（stage 6）：密碼正確後才告知停用，停用時不發放權杖。 */
class StoreLoginSuspensionTest {

    private final StoreRepository storeRepository = mock(StoreRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final JwtUtils jwtUtils = mock(JwtUtils.class);
    private final StoreService service = new StoreService(storeRepository);

    StoreLoginSuspensionTest() {
        ReflectionTestUtils.setField(service, "passwordEncoder", passwordEncoder);
        ReflectionTestUtils.setField(service, "jwtUtils", jwtUtils);
    }

    private Store store(boolean suspended) {
        Store store = new Store();
        store.setId(20L);
        store.setStoreName("測試店");
        store.setAccount("acc");
        store.setPasswordHash("hash");
        store.setRole("STORE");
        store.setSuspended(suspended);
        return store;
    }

    @Test
    @DisplayName("分店被停用：密碼正確也不發放權杖（403）")
    void suspendedStoreCannotLogIn() {
        when(storeRepository.findFirstByAccount("acc")).thenReturn(Optional.of(store(true)));
        when(passwordEncoder.matches("pw", "hash")).thenReturn(true);

        CustomException e = assertThrows(CustomException.class, () -> service.login("acc", "pw"));

        assertEquals("403", e.getCode());
        verify(jwtUtils, never()).generateToken(any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("所屬品牌被停用：分店密碼正確也不發放權杖（403）")
    void storeOfSuspendedBrandCannotLogIn() {
        when(storeRepository.findFirstByAccount("acc")).thenReturn(Optional.of(store(false)));
        when(passwordEncoder.matches("pw", "hash")).thenReturn(true);
        when(storeRepository.isBrandSuspended(20L)).thenReturn(true);

        CustomException e = assertThrows(CustomException.class, () -> service.login("acc", "pw"));

        assertEquals("403", e.getCode());
        verify(jwtUtils, never()).generateToken(any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("密碼錯誤仍回 401，不因停用狀態改變回應")
    void wrongPasswordIsAlways401() {
        when(storeRepository.findFirstByAccount("acc")).thenReturn(Optional.of(store(true)));
        when(passwordEncoder.matches("bad", "hash")).thenReturn(false);

        CustomException e = assertThrows(CustomException.class, () -> service.login("acc", "bad"));

        assertEquals("401", e.getCode());
    }

    @Test
    @DisplayName("分店與品牌都未停用時發放權杖")
    void activeStoreGetsAToken() {
        when(storeRepository.findFirstByAccount("acc")).thenReturn(Optional.of(store(false)));
        when(passwordEncoder.matches("pw", "hash")).thenReturn(true);
        when(jwtUtils.generateToken(any(), any(), any(), anyInt())).thenReturn("store-token");

        Map<String, Object> data = service.login("acc", "pw");

        assertEquals("store-token", data.get("token"));
    }
}
