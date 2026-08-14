package gtalent.stockpulse.metric;

import gtalent.stockpulse.gateway.QuoteWebSocketHandler;
import gtalent.stockpulse.model.Tick;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 原本設計裡 Kafka Consumer 的角色，在單體 MVP 中改用 Spring 應用內事件監聽取代。
 * 好處：不需要額外的 Kafka 基礎設施，缺點：只能在單一 JVM 內運作，無法水平擴展到多台機器，
 * 這是前面提過「拿掉 Kafka」的具體代價。
 */
@Component
public class TickListener {

    private final QuoteWebSocketHandler webSocketHandler;
    // 實際專案中這裡會注入 FundamentalsRepository 從 Supabase 查詢基本面資料

    public TickListener(QuoteWebSocketHandler webSocketHandler) {
        this.webSocketHandler = webSocketHandler;
    }

    @EventListener
    public void onTick(Tick tick) {
        // MVP 階段先直接推送價格，指標計算邏輯可以直接沿用先前設計的 StockMetrics.of(...)
        var payload = """
                {"symbol":"%s","price":%s,"timestamp":"%s"}
                """.formatted(tick.symbol().code(), tick.price(), tick.timestamp());

        webSocketHandler.broadcast(tick.symbol().code(), payload);
    }
}