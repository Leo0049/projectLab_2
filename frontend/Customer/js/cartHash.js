/**
 * Cart Item Hashing Utility
 * 用提供一致的雜湊值，用於判斷購物車品項是否相同。
 */
const CartHash = {
    /**
     * 產生品項雜湊值
     * 維度：userId, productId, size, sugar, ice, toppings (排序後)
     */
    async generate(item) {
        const userId = item.userId || localStorage.getItem('JOIN_USER_ID') || 'guest';
        const productId = item.productId || 0;
        const size = (item.sizeSnapshot || item.size || '').trim();
        const sugar = (item.sugarSnapshot || item.sugar || '').trim();
        const ice = (item.iceSnapshot || item.ice || '').trim();
        const couponId = item.couponId || 0;
        
        // 配料排序確保一致性
        const toppings = Array.isArray(item.toppingNames) ? [...item.toppingNames] : [];
        toppings.sort();
        const toppingsKey = toppings.join(',');

        // 組合成原始字串
        const rawKey = `u:${userId}|p:${productId}|sz:${size}|su:${sugar}|ic:${ice}|tp:${toppingsKey}|cp:${couponId}`;

        // 使用 SubtleCrypto 產生 SHA-256 雜湊 (瀏覽器原生支援)
        const encoder = new TextEncoder();
        const data = encoder.encode(rawKey);
        const hashBuffer = await crypto.subtle.digest('SHA-256', data);
        const hashArray = Array.from(new Uint8Array(hashBuffer));
        const hashHex = hashArray.map(b => b.toString(16).padStart(2, '0')).join('');
        
        // 取前 32 位以符合一般 MD5 長度慣例
        return hashHex.substring(0, 32);
    }
};

window.CartHash = CartHash;
