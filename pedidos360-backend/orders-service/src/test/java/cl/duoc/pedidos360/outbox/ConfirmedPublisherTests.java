package cl.duoc.pedidos360.outbox;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class ConfirmedPublisherTests {
 @Test void marksSuccessOnlyAfterConfirmAndNoReturn() throws Exception {
  RabbitTemplate rabbit=mock(RabbitTemplate.class);
  doAnswer(call->{CorrelationData c=call.getArgument(3); c.getFuture().complete(new CorrelationData.Confirm(true,null)); return null;})
    .when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
  new ConfirmedPublisher(rabbit).publish("exchange","route","id","{}");
  verify(rabbit).send(eq("exchange"),eq("route"),argThat(m->m.getMessageProperties().getDeliveryMode()==MessageDeliveryMode.PERSISTENT && m.getMessageProperties().getMessageId().equals("id")),any(CorrelationData.class));
 }
 @Test void returnIsFailureEvenWhenBrokerConfirms() {
  RabbitTemplate rabbit=mock(RabbitTemplate.class);
  doAnswer(call->{CorrelationData c=call.getArgument(3); c.setReturned(new ReturnedMessage(new Message(new byte[0]),312,"NO_ROUTE","exchange","route"));c.getFuture().complete(new CorrelationData.Confirm(true,null));return null;})
    .when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
  assertThrows(IllegalStateException.class,()->new ConfirmedPublisher(rabbit).publish("exchange","route","id","{}"));
 }
 @Test void negativeConfirmDoesNotMarkSuccess() {
  RabbitTemplate rabbit=mock(RabbitTemplate.class);
  doAnswer(call->{CorrelationData c=call.getArgument(3);c.getFuture().complete(new CorrelationData.Confirm(false,"unavailable"));return null;})
    .when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
  assertThrows(IllegalStateException.class,()->new ConfirmedPublisher(rabbit).publish("exchange","route","id","{}"));
 }
}
