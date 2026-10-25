package cl.duoc.pedidos360.common;
import java.time.Instant;
public record OrderEvent(String eventId, long orderId, String customer, String product, int quantity, Instant occurredAt) {}
