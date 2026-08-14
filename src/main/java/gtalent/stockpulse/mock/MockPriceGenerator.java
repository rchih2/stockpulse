package gtalent.stockpulse.mock;

import gtalent.stockpulse.model.Symbol;
import gtalent.stockpulse.model.Tick;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 用隨機漫步模擬股價變動，讓你在還沒串接真實行情 API 前，
 * 就能先驗證「報價 → 計算 → 推送 → 前端顯示」整條資料流是否正常。
 * 之後要換成真實行情，只需要把這個 class 換成呼叫真實 API 的 Ingestion Service，
 * 下游的 MetricEngine、WebSocket 推送邏輯完全不用改。
 */
@Component
public class MockPriceGenerator {

    private static final List<Symbol> WATCH_LIST = List.of(
            new Symbol("2330", "TW"),
            new Symbol("2317", "TW"),
            new Symbol("0050", "TW")
    );

    // 記住每檔股票目前的模擬價格，讓下一次波動基於這次的價格（隨機漫步）
    private final Map<Symbol, BigDecimal> lastPrices = new ConcurrentHashMap<>(Map.of(
            WATCH_LIST.get(0), BigDecimal.valueOf(950),
            WATCH_LIST.get(1), BigDecimal.valueOf(105),
            WATCH_LIST.get(2), BigDecimal.valueOf(135)
    ));

    private final ApplicationEventPublisher eventPublisher;

    public MockPriceGenerator(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    // 每 3 秒模擬一次報價變動；Render 免費方案資源有限，不建議設太短的間隔
    @Scheduled(fixedRate = 3000)
    public void generateTick() {
        for (var symbol : WATCH_LIST) {
            var lastPrice = lastPrices.get(symbol);
            var changePercent = ThreadLocalRandom.current().nextDouble(-0.02, 0.02); // ±2% 隨機波動
            var newPrice = lastPrice.multiply(BigDecimal.valueOf(1 + changePercent))
                    .setScale(2, java.math.RoundingMode.HALF_UP);

            lastPrices.put(symbol, newPrice);

            var tick = new Tick(symbol, newPrice,
                    ThreadLocalRandom.current().nextLong(100, 10000),
                    Instant.now());

            eventPublisher.publishEvent(tick);
        }
    }
}