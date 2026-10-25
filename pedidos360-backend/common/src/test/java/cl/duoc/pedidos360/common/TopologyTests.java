package cl.duoc.pedidos360.common;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.amqp.core.*;
import static org.junit.jupiter.api.Assertions.*;
class TopologyTests {
 @Test void declaresThreeDurableDomainsAndTheirDeadLetterRoutesFromSharedConfig() throws Exception {
  Class<?> config = assertDoesNotThrow(() -> Class.forName("cl.duoc.pedidos360.common.config.TopologyConfig"));
  try(var context=new AnnotationConfigApplicationContext(config)) {
   assertEquals(6,context.getBeansOfType(Queue.class).size());
   assertEquals(3,context.getBeansOfType(Exchange.class).size());
   assertEquals(6,context.getBeansOfType(Binding.class).size());
   Queue notification=context.getBean("notificationQueue",Queue.class);
   assertTrue(notification.isDurable());assertFalse(notification.isAutoDelete());
   assertEquals("pedidos360.notifications",notification.getName());
   assertEquals("pedidos360.dead",notification.getArguments().get("x-dead-letter-exchange"));
   assertEquals("notification.failed",notification.getArguments().get("x-dead-letter-routing-key"));
   assertEquals("classic",notification.getArguments().get("x-queue-type"));
   assertEquals("order.*",context.getBean("notificationBinding",Binding.class).getRoutingKey());
  }
 }
}
