package com.example.demo.repository;

import com.example.demo.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

// ✏️ 修改：類別名稱改為單數 UserRepository（原本是 UsersRepository）
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findFirstByName(String name);

    Optional<User> findByPhone(String phone);

    Optional<User> findByRole(String role);

    boolean existsByPhone(String phone);

    /**
     * 取得使用者並鎖定該列（SELECT ... FOR UPDATE），供餘額異動使用。
     *
     * ⚠️ 動到 balance 的流程一律要用這個方法，不可用 findById。
     *    餘額是「讀出 → 相加 → 寫回」，若不鎖列，併發請求會互相覆蓋（lost update）。
     *    實測 20 個併發各儲值 10 元，最終只入帳 70 元，且 transaction_records
     *    的總額（120）與 users.balance（70）對不起來，等於帳無法對。
     *
     *    必須在交易中呼叫，鎖會持有到交易結束。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :userId")
    Optional<User> findByIdForUpdate(@Param("userId") Long userId);
}
