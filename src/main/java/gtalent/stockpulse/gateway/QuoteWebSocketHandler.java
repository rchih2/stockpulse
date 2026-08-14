package gtalent.stockpulse.gateway;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// TickListener 呼叫 broadcast(...) 把價格推送給訂閱該股票的手機端連線
@Component
public class QuoteWebSocketHandler extends TextWebSocketHandler {

    // symbol -> 訂閱該 symbol 的 sessions
    private final Map<String, Set<WebSocketSession>> subscriptions = new ConcurrentHashMap<>();

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // 前端傳來 "SUBSCRIBE 2330" 之類指令（見 index.html）
        String payload = message.getPayload();
        if (payload.startsWith("SUBSCRIBE ")) {
            String symbol = payload.substring("SUBSCRIBE ".length()).trim();
            subscriptions.computeIfAbsent(symbol, k -> ConcurrentHashMap.newKeySet())
                    .add(session);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, org.springframework.web.socket.CloseStatus status) {
        // 連線關閉時清掉這個 session，避免訂閱清單裡累積失效連線
        subscriptions.values().forEach(sessions -> sessions.remove(session));
    }

    public void broadcast(String symbol, String jsonPayload) {
        var sessions = subscriptions.get(symbol);
        if (sessions == null) return;

        for (var session : sessions) {
            // 每個推送用 virtual thread 執行，避免單一慢連線阻塞其他推送
            Thread.ofVirtual().start(() -> {
                try {
                    if (session.isOpen()) {
                        session.sendMessage(new TextMessage(jsonPayload));
                    }
                } catch (Exception e) {
                    // MVP 階段先簡單忽略單一連線的推送失敗，正式版建議加上 log
                }
            });
        }
    }
}