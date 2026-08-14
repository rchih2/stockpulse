package gtalent.stockpulse.model;

// 股票代碼與市場，用 record 表示不可變值物件
// MockPriceGenerator 用它當 Map 的 key，record 自動提供 equals()/hashCode()，不需要額外實作
public record Symbol(String code, String market) {
    public Symbol {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code 不可為空");
        }
    }
}