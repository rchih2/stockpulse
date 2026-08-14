package gtalent.stockpulse.model;

import java.math.BigDecimal;
import java.time.Instant;

// 單筆即時報價 tick，MockPriceGenerator 產生它、TickListener 消費它
public record Tick(
        Symbol symbol,
        BigDecimal price,
        long volume,
        Instant timestamp
) {}