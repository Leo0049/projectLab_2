package com.example.demo.service;

import com.example.demo.dto.CreateProductRequest;
import com.example.demo.entity.Brand;
import com.example.demo.entity.MenuCategory;
import com.example.demo.exception.CustomException;
import com.example.demo.repository.BrandRepository;
import com.example.demo.repository.BrandSpecSettingRepository;
import com.example.demo.repository.BrandToppingSettingRepository;
import com.example.demo.repository.MenuCategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BrandServiceReferenceTest {
    @Mock private BrandRepository brandRepository;
    @Mock private MenuCategoryRepository menuCategoryRepository;
    @Mock private BrandSpecSettingRepository brandSpecSettingRepository;
    @Mock private BrandToppingSettingRepository brandToppingSettingRepository;

    private BrandService brandService;

    @BeforeEach
    void setUp() {
        brandService = new BrandService();
        ReflectionTestUtils.setField(brandService, "brandRepository", brandRepository);
        ReflectionTestUtils.setField(brandService, "menuCategoryRepository", menuCategoryRepository);
        ReflectionTestUtils.setField(brandService, "brandSpecSettingRepository", brandSpecSettingRepository);
        ReflectionTestUtils.setField(brandService, "brandToppingSettingRepository", brandToppingSettingRepository);

        Brand brand = new Brand();
        brand.setId(1L);
        when(brandRepository.findById(1L)).thenReturn(Optional.of(brand));
    }

    @Test
    void rejectsCategoryOwnedByAnotherBrand() {
        Brand otherBrand = new Brand();
        otherBrand.setId(2L);
        MenuCategory foreignCategory = new MenuCategory();
        foreignCategory.setBrand(otherBrand);
        when(menuCategoryRepository.findById(8L)).thenReturn(Optional.of(foreignCategory));
        CreateProductRequest request = new CreateProductRequest();
        request.setCategoryId(8L);

        assertThrows(CustomException.class, () -> brandService.createProduct(1L, request));
    }

    @Test
    void rejectsSpecAndToppingIdsNotOwnedByTheBrand() {
        when(brandSpecSettingRepository.findByBrandIdOrderBySortOrderAscIdAsc(1L)).thenReturn(List.of());
        when(brandToppingSettingRepository.findByBrandId(1L)).thenReturn(List.of());

        CreateProductRequest foreignSpec = new CreateProductRequest();
        foreignSpec.setBrandSpecIds(List.of(81L));
        assertThrows(CustomException.class, () -> brandService.createProduct(1L, foreignSpec));

        CreateProductRequest foreignTopping = new CreateProductRequest();
        foreignTopping.setBrandToppingIds(List.of(82L));
        assertThrows(CustomException.class, () -> brandService.createProduct(1L, foreignTopping));
    }
}
