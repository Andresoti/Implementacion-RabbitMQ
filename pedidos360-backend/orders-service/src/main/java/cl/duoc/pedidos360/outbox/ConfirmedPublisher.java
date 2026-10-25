package cl.duoc.pedidos360.outbox;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.stereotype.Component;
@Component
public class ConfirmedPublisher {
 private final RabbitTemplate rabbit;
 public ConfirmedPublisher(RabbitTemplate rabbit){this.rabbit=rabbit;this.rabbit.setMandatory(true);}
 public void publish(String exchange,String route,String eventId,String payload) {
  var properties=new MessageProperties();
  properties.setMessageId(eventId);properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
  properties.setContentEncoding(StandardCharsets.UTF_8.name());properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
  var correlation=new CorrelationData(java.util.UUID.randomUUID().toString());
  rabbit.send(exchange,route,new Message(payload.getBytes(StandardCharsets.UTF_8),properties),correlation);
  try {
   var confirm=correlation.getFuture().get(10,TimeUnit.SECONDS);
   if(!confirm.ack() || correlation.getReturned()!=null) throw new IllegalStateException("Publication not routed and confirmed");
  } catch(InterruptedException e) {Thread.currentThread().interrupt();throw new IllegalStateException("Publication interrupted");}
    catch(java.util.concurrent.ExecutionException|java.util.concurrent.TimeoutException e) {throw new IllegalStateException("Publication unconfirmed");}
 }
}
