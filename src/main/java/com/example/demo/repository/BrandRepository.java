package com.example.demo.repository;

import com.example.demo.entity.Brand;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface BrandRepository extends JpaRepository<Brand, Long> {

    Optional<Brand> findByAccount(String account);

    boolean existsByAccount(String account);


    boolean existsByName(String name);

    /** 公開的品牌列表只取指定審核狀態的品牌（N-28）。 */
    List<Brand> findByApprovalStatus(String approvalStatus);

    /** 公開的品牌列表：已核准且未停用（N-28、stage 6）。 */
    List<Brand> findByApprovalStatusAndSuspendedFalse(String approvalStatus);

    /** 管理員的待審清單與已核准清單（分頁，N-28）。 */
    Page<Brand> findByApprovalStatus(String approvalStatus, Pageable pageable);

    /**
     * 條件式更新審核狀態（N-28）：只有目前狀態等於 {@code currentStatus} 時才會寫入，回傳受影響的列數。
     * 不採「讀出、檢查、寫回」，因為兩位管理員同時核准與退件時，那種寫法會讓後寫者覆蓋先寫者。
     *
     * <p>參數名不用 {@code from}：它是 JPQL 的保留字，避免解析器把它當成關鍵字。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Brand b SET b.approvalStatus = :target, b.reviewedAt = :reviewedAt "
            + "WHERE b.id = :brandId AND b.approvalStatus = :currentStatus")
    int transitionApprovalStatus(@Param("brandId") Long brandId,
                                 @Param("currentStatus") String currentStatus,
                                 @Param("target") String target,
                                 @Param("reviewedAt") LocalDateTime reviewedAt);

    /**
     * 條件式停用（stage 6）：只停用「已核准且未停用」的品牌，同時遞增 tokenVersion，
     * 讓品牌既有的權杖立即失效。回傳 0 代表條件不成立（不存在、未核准，或已停用），由呼叫端區分 404／409。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Brand b SET b.suspended = true, b.suspendedAt = :at, b.tokenVersion = b.tokenVersion + 1 "
            + "WHERE b.id = :brandId AND b.suspended = false AND b.approvalStatus = :approved")
    int suspendBrand(@Param("brandId") Long brandId,
                     @Param("approved") String approved,
                     @Param("at") LocalDateTime at);

    /**
     * 條件式恢復（stage 6）：只恢復「已停用」的品牌。不遞增版本：停用時舊權杖已經失效，
     * 恢復後必須重新登入才能取得新權杖。回傳 0 代表品牌未停用或不存在。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Brand b SET b.suspended = false, b.suspendedAt = null "
            + "WHERE b.id = :brandId AND b.suspended = true")
    int reinstateBrand(@Param("brandId") Long brandId);
}
