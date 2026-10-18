package com.example.demo.service;

import com.example.demo.common.BrandApproval;
import com.example.demo.common.JwtUtils;
import com.example.demo.entity.Brand;
import com.example.demo.exception.CustomException;
import com.example.demo.repository.BrandRepository;
import com.example.demo.repository.BrandSpecSettingRepository;
import com.example.demo.repository.BrandToppingSettingRepository;
import com.example.demo.repository.SpecMasterRepository;
import com.example.demo.repository.ToppingMasterRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 品牌自助註冊與登入的審核規則（N-28）。 */
@ExtendWith(MockitoExtension.class)
class BrandServiceApprovalTest {

    @Mock
    private BrandRepository brandRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtUtils jwtUtils;
    @Mock
    private SpecMasterRepository specMasterRepository;
    @Mock
    private ToppingMasterRepository toppingMasterRepository;
    @Mock
    private BrandSpecSettingRepository brandSpecSettingRepository;
    @Mock
    private BrandToppingSettingRepository brandToppingSettingRepository;
    @InjectMocks
    private BrandService brandService;

    private static Brand brand(String status) {
        Brand brand = new Brand();
        brand.setAccount("acc");
        brand.setName("測試品牌");
        brand.setPasswordHash("hash");
        brand.setApprovalStatus(status);
        return brand;
    }

    @Test
    @DisplayName("自助註冊的品牌一律建立為 PENDING")
    void registerCreatesAPendingBrand() {
        when(brandRepository.existsByAccount("acc")).thenReturn(false);
        when(brandRepository.existsByName(any())).thenReturn(false);
        when(passwordEncoder.encode("pw")).thenReturn("hash");

        brandService.register("測試品牌", "acc", "pw");

        ArgumentCaptor<Brand> saved = ArgumentCaptor.forClass(Brand.class);
        verify(brandRepository).save(saved.capture());
        assertEquals(BrandApproval.PENDING, saved.getValue().getApprovalStatus());
    }

    @Test
    @DisplayName("待審品牌即使密碼正確也不能登入，且不發放權杖（N-28）")
    void pendingBrandCannotLogInEvenWithTheRightPassword() {
        when(brandRepository.findByAccount("acc")).thenReturn(Optional.of(brand("PENDING")));
        when(passwordEncoder.matches("pw", "hash")).thenReturn(true);

        CustomException e = assertThrows(CustomException.class, () -> brandService.login("acc", "pw"));

        assertEquals("403", e.getCode());
        verify(jwtUtils, never()).generateToken(any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("密碼錯誤時回 401，不因審核狀態而改變回應，因此不洩漏審核進度")
    void wrongPasswordIsAlwaysA401RegardlessOfStatus() {
        when(brandRepository.findByAccount("acc")).thenReturn(Optional.of(brand("PENDING")));
        when(passwordEncoder.matches("bad", "hash")).thenReturn(false);

        CustomException e = assertThrows(CustomException.class, () -> brandService.login("acc", "bad"));

        assertEquals("401", e.getCode());
    }

    @Test
    @DisplayName("退件的品牌即使密碼正確也不能登入（回 403）")
    void rejectedBrandCannotLogIn() {
        when(brandRepository.findByAccount("acc")).thenReturn(Optional.of(brand("REJECTED")));
        when(passwordEncoder.matches("pw", "hash")).thenReturn(true);

        CustomException e = assertThrows(CustomException.class, () -> brandService.login("acc", "pw"));

        assertEquals("403", e.getCode());
    }

    @Test
    @DisplayName("核准後的品牌登入取得權杖")
    void approvedBrandGetsAToken() {
        when(brandRepository.findByAccount("acc")).thenReturn(Optional.of(brand("APPROVED")));
        when(passwordEncoder.matches("pw", "hash")).thenReturn(true);
        when(jwtUtils.generateToken(any(), any(), any(), anyInt())).thenReturn("brand-token");

        Map<String, Object> data = brandService.login("acc", "pw");

        assertEquals("brand-token", data.get("token"));
    }
}
