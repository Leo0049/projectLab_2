package com.example.demo.repository;

import com.example.demo.common.StoreVisibility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文字掃描：對顧客公開的分店列表查詢，WHERE 必須包含 {@link StoreVisibility#FILTER}（stage 6）。
 *
 * <p>這些查詢的字串是編譯期常值，直接讀註解即可比對，不需要資料庫。
 * 若有人新增公開列表卻忘了套用停用條件，請把方法名稱加入這裡。
 */
class StoreVisibilityGuardTest {

    private static final Set<String> PUBLIC_LISTING_QUERIES = Set.of(
            "searchByBrandName",
            "countSearchByKeyword",
            "searchByKeywordWithDistance",
            "findNearbyStores",
            "findAllVisible",
            "findVisibleOrderByIdAsc",
            "findVisibleByBrandIdOrderByIdAsc");

    @Test
    @DisplayName("每一條公開的分店列表查詢都套用 StoreVisibility.FILTER（含計數查詢）")
    void everyPublicListingQueryAppliesTheSharedFilter() {
        Set<String> found = new HashSet<>();
        for (Method method : StoreRepository.class.getDeclaredMethods()) {
            if (!PUBLIC_LISTING_QUERIES.contains(method.getName())) {
                continue;
            }
            Query query = method.getAnnotation(Query.class);
            assertNotNull(query, method.getName() + " 缺少 @Query");
            assertTrue(query.value().contains(StoreVisibility.FILTER),
                    method.getName() + " 的 WHERE 未包含 StoreVisibility.FILTER");
            found.add(method.getName());
        }
        assertEquals(PUBLIC_LISTING_QUERIES, found, "清單中的方法必須都存在於 StoreRepository");
    }
}
