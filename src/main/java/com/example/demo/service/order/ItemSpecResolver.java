package com.example.demo.service.order;

import com.example.demo.entity.ProductSpecRelation;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 決定一筆訂單品項最終要落下的甜度／冰量／杯型。
 *
 * <p>規則只有一條，但它是**防竄改**規則，不是顯示邏輯：
 * 某個規格類型若在該商品底下只有唯一一個選項（例如「熱美式」只有「熱飲」一種冰量），
 * 就一律採用那個選項，**不採信用戶端送來的值**。前端下拉選單只會顯示那一個選項，
 * 但請求是可以偽造的——沒有這條規則，攻擊者可以送出「冰的熱美式」，
 * 甚至送出別的品牌才有的規格名稱，讓門市收到一張做不出來的單。
 *
 * <p>原本這段邏輯在 {@code GroupOrderService.addItem} 與 {@code updateItem} 各寫了一次
 * （共約 40 行、三個規格類型各一份），兩邊的行為差異只在「新增時沒帶就用空字串、
 * 更新時沒帶就不動」。抽出來之後兩邊共用同一份規則，也才測得到。
 */
public final class ItemSpecResolver {

    /** 對應 brand_spec_setting.spec_type 的三種值 */
    public static final String SWEETNESS = "SWEETNESS";
    public static final String ICE = "ICE";
    public static final String SIZE = "SIZE";

    private final Map<String, List<ProductSpecRelation>> byType;

    private ItemSpecResolver(Map<String, List<ProductSpecRelation>> byType) {
        this.byType = byType;
    }

    /**
     * @param relations 該商品的所有規格關聯（{@code ProductSpecRelationRepository.findByIdProductId}）
     */
    public static ItemSpecResolver of(List<ProductSpecRelation> relations) {
        if (relations == null || relations.isEmpty()) {
            return new ItemSpecResolver(Collections.emptyMap());
        }
        Map<String, List<ProductSpecRelation>> byType = relations.stream()
                .filter(r -> r.getBrandSpec() != null && r.getBrandSpec().getSpecType() != null)
                .collect(Collectors.groupingBy(r -> r.getBrandSpec().getSpecType().toUpperCase()));
        return new ItemSpecResolver(byType);
    }

    /**
     * 這個規格類型是否為「固定規格」——只有唯一選項，因此不接受用戶端指定。
     */
    public boolean isFixed(String specType) {
        List<ProductSpecRelation> options = byType.get(normalize(specType));
        return options != null && options.size() == 1;
    }

    /**
     * 解析出最終要存進快照的值。
     *
     * @param specType  SWEETNESS / ICE / SIZE
     * @param requested 用戶端送來的值（可為 null）
     * @return 固定規格時回傳那個唯一選項的名稱；否則原樣回傳 requested
     */
    public String resolve(String specType, String requested) {
        List<ProductSpecRelation> options = byType.get(normalize(specType));
        if (options != null && options.size() == 1) {
            return options.get(0).getBrandSpec().getCustomName();
        }
        return requested;
    }

    /** 新增品項用：沒帶值時以空字串落下，與既有資料格式一致（欄位不可為 null） */
    public String resolveOrEmpty(String specType, String requested) {
        String resolved = resolve(specType, requested);
        return resolved == null ? "" : resolved;
    }

    private static String normalize(String specType) {
        return specType == null ? "" : specType.toUpperCase();
    }
}
