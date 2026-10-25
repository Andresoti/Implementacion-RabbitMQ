package cl.duoc.pedidos360.worker;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import com.rabbitmq.client.Channel;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class DomainConsumersTests {
 @ParameterizedTest @CsvSource({"notification,NOTIFICATION,notification-queue","ticket,TICKET,ticket-queue","document,DOCUMENT,document-queue"})
 void domainsHaveIndependentManualListenersUsingCentralQueueConfiguration(String name,Domain domain,String property)throws Exception{
  String type="cl.duoc.pedidos360.worker.consumer."+Character.toUpperCase(name.charAt(0))+name.substring(1)+"Consumer";
  Class<?> consumer=assertDoesNotThrow(()->Class.forName(type));
  var handler=mock(DeliveryHandler.class);var instance=consumer.getConstructor(DeliveryHandler.class).newInstance(handler);
  var listener=consumer.getMethod("receive",Message.class,Channel.class);
  var annotation=listener.getAnnotation(RabbitListener.class);
  assertNotNull(annotation);assertEquals("MANUAL",annotation.ackMode());
  assertArrayEquals(new String[]{"${app.rabbit."+property+"}"},annotation.queues());
  Message message=new Message(new byte[0]);Channel channel=mock(Channel.class);
  listener.invoke(instance,message,channel);verify(handler).handle(message,channel,domain);
 }
}
