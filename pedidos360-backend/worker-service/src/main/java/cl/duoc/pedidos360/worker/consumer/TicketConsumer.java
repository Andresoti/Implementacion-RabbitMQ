package cl.duoc.pedidos360.worker.consumer;
import cl.duoc.pedidos360.worker.*;
import org.springframework.stereotype.Component;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.core.Message;
import com.rabbitmq.client.Channel;
import java.io.IOException;
@Component
public class TicketConsumer {
 private final DeliveryHandler handler;
 public TicketConsumer(DeliveryHandler handler){this.handler=handler;}
 @RabbitListener(queues="${app.rabbit.ticket-queue}",ackMode="MANUAL")
 public void receive(Message message,Channel channel)throws IOException{handler.handle(message,channel,Domain.TICKET);}
}
