package com.example.demo.service.order;

import java.math.BigDecimal;
import java.util.List;

/**
 * 團員取消自己品項時的金額計算（純函式，可單獨測試）。
 *
 * <p>⚠️ 原本這段邏輯直接寫在 {@code GroupOrderService.cancelMemberItems}，而且有兩個缺陷：
 *
 * <ol>
 *   <li><b>憑空生出餘額</b>：只要品項不是 PAID，就把金額累加到「退給團長」的總額並無條件退款。
 *       揪團還在 OPEN（尚未結帳、團長一毛未付）時，團員一取消，團長錢包就直接多一筆錢——
 *       兩個帳號即可無限重複把品項金額變成餘額。</li>
 *   <li><b>多退折扣</b>：退給團長用的是未扣折扣的 {@code finalPrice}，但團長當初代墊的是
 *       折扣後的淨額（結帳時 escrow 以「總額 − 各品項折扣」計算）。</li>
 * </ol>
 *
 * <p>因此規則收斂成三條：
 * <ul>
 *   <li>{@code PAID}（團員自己付過了）→ 退<strong>團員</strong>淨額 {@code finalPrice − discount}。</li>
 *   <li>{@code ESCROWED}（團長代墊）→ 退<strong>團長</strong>淨額，但以 {@code escrowAmount} 為上限——
 *       沒被代墊過的金額不得退款。</li>
 *   <li>其餘（{@code UNPAID} 等，代表還沒有任何錢進出）→ <strong>完全不動錢</strong>，只刪品項。</li>
 * </ul>
 *
 * <p>{@code escrowAmount} 是「這一團實際被扣過的錢」，是唯一可信的付款上限；
 * 沒有它就沒有證據說團長付過錢。
 */
public final class MemberCancelSettlement {

    private MemberCancelSettlement() {
    }

    public static final String PAID = "PAID";
    public static final String ESCROWED = "ESCROWED";

    /** 單一品項在取消時需要知道的資訊 */
    public record Item(boolean paid, boolean escrowed, BigDecimal finalPrice, BigDecimal discount) {

        /** 由資料庫的 paymentStatus 字串建立（大小寫不敏感，與其他流程一致的寫法） */
        public static Item of(String paymentStatus, BigDecimal finalPrice, BigDecimal discount) {
            String status = paymentStatus == null ? "" : paymentStatus.trim();
            return new Item(PAID.equalsIgnoreCase(status), ESCROWED.equalsIgnoreCase(status),
                    finalPrice, discount);
        }

        /** 這一項實際被付出過的淨額（負值與 null 一律視為 0） */
        public BigDecimal netAmount() {
            BigDecimal gross = finalPrice == null ? BigDecimal.ZERO : finalPrice;
            BigDecimal off = discount == null ? BigDecimal.ZERO : discount;
            return gross.subtract(off).max(BigDecimal.ZERO);
        }
    }

    /**
     * @param memberRefund    應退給團員的金額
     * @param hostRefund      應退給團長的金額（已受 escrow 上限約束）
     * @param remainingEscrow 退款後剩下的代墊金額
     * @param amountRemoved   本次實際離開系統的總金額（= memberRefund + hostRefund）
     */
    public record Result(BigDecimal memberRefund, BigDecimal hostRefund,
                         BigDecimal remainingEscrow, BigDecimal amountRemoved) {
    }

    public static Result settle(List<Item> items, BigDecimal escrowAmount) {
        BigDecimal escrow = escrowAmount == null ? BigDecimal.ZERO
                : escrowAmount.max(BigDecimal.ZERO);

        BigDecimal memberRefund = BigDecimal.ZERO;
        BigDecimal hostCandidate = BigDecimal.ZERO;

        for (Item item : items == null ? List.<Item>of() : items) {
            if (item.paid()) {
                memberRefund = memberRefund.add(item.netAmount());
            } else if (item.escrowed()) {
                hostCandidate = hostCandidate.add(item.netAmount());
            }
            // UNPAID／其他狀態：沒有任何錢進出，不退款
        }

        // 最多只退「真的被代墊過」的金額
        BigDecimal hostRefund = hostCandidate.min(escrow);
        return new Result(memberRefund, hostRefund, escrow.subtract(hostRefund),
                memberRefund.add(hostRefund));
    }
}
