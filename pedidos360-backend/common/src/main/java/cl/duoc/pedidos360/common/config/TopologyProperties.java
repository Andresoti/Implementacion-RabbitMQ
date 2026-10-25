package cl.duoc.pedidos360.common.config;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties("app.rabbit")
public record TopologyProperties(String ordersExchange, String tasksExchange, String deadExchange, String orderRoute, String ticketRoute, String notificationQueue, String documentQueue, String ticketQueue, String notificationDlq, String documentDlq, String ticketDlq, String notificationBinding, String documentBinding, String notificationFailure, String documentFailure, String ticketFailure) {}
