package gtalent.stockpulse.config;

import gtalent.stockpulse.gateway.QuoteWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

// 把 QuoteWebSocketHandler 註冊到 /ws 這個路徑，
// index.html 裡的 new WebSocket(... + '/ws') 就是連到這裡
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final QuoteWebSocketHandler quoteWebSocketHandler;

    public WebSocketConfig(QuoteWebSocketHandler quoteWebSocketHandler) {
        this.quoteWebSocketHandler = quoteWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(quoteWebSocketHandler, "/ws")
                .setAllowedOrigins("*"); // MVP 階段先開放所有來源，正式版建議改成白名單
    }
}