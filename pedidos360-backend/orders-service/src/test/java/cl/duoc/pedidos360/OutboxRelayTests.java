package cl.duoc.pedidos360;
import cl.duoc.pedidos360.outbox.*;
import cl.duoc.pedidos360.service.PedidoService;
import cl.duoc.pedidos360.dto.CreatePedidoRequest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:relay;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","app.security.issuer=http://localhost:9998"})
@ActiveProfiles("test")
class OutboxRelayTests {
 @Autowired JdbcTemplate jdbc; @Autowired PedidoService orders; @Autowired OutboxRelay relay;
 @MockitoBean ConfirmedPublisher publisher;
 @BeforeEach void clean(){jdbc.update("delete from outbox");jdbc.update("delete from pedido");reset(publisher);}
 @Test void confirmedRowsArePublishedPerDestinationAndNeverSelectedAgain() {
  orders.create(new CreatePedidoRequest("Ana","Book",2));
  assertTrue(relay.dispatchOne());assertTrue(relay.dispatchOne());assertFalse(relay.dispatchOne());
  assertEquals(2,jdbc.queryForObject("select count(*) from outbox where published_at is not null",Integer.class));
  verify(publisher).publish(eq("pedidos360.orders"),eq("order.created"),anyString(),anyString());
  verify(publisher).publish(eq("pedidos360.tasks"),eq("ticket.requested"),anyString(),anyString());
 }
 @Test void concurrentRelaySkipsLockedIntentUntilPublisherConfirmation() throws Exception {
  orders.create(new CreatePedidoRequest("Ana","Book",2));
  jdbc.update("update outbox set published_at=CURRENT_TIMESTAMP where destination='TICKET'");
  var publishing=new java.util.concurrent.CountDownLatch(1);
  var confirm=new java.util.concurrent.CountDownLatch(1);
  doAnswer(call->{publishing.countDown();assertTrue(confirm.await(5,java.util.concurrent.TimeUnit.SECONDS));return null;})
   .when(publisher).publish(anyString(),anyString(),anyString(),anyString());
  var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
  try {
   var first=executor.submit(()->relay.dispatchOne());
   assertTrue(publishing.await(5,java.util.concurrent.TimeUnit.SECONDS));
   var second=executor.submit(()->relay.dispatchOne());
   assertFalse(second.get(5,java.util.concurrent.TimeUnit.SECONDS));
   assertEquals(1,jdbc.queryForObject("select count(*) from outbox where published_at is null",Integer.class));
   confirm.countDown();assertTrue(first.get(5,java.util.concurrent.TimeUnit.SECONDS));
  } finally {confirm.countDown();executor.shutdownNow();}
  verify(publisher,times(1)).publish(anyString(),anyString(),anyString(),anyString());
  assertEquals(0,jdbc.queryForObject("select count(*) from outbox where published_at is null",Integer.class));
 }
 @Test void publicationFailureLeavesDurableIntentAndSchedulesRetry() {
  orders.create(new CreatePedidoRequest("Ana","Book",2));
  doThrow(new IllegalStateException("unconfirmed")).when(publisher).publish(anyString(),anyString(),anyString(),anyString());
  assertTrue(relay.dispatchOne());
  assertEquals(0,jdbc.queryForObject("select count(*) from outbox where published_at is not null",Integer.class));
  assertEquals(1,jdbc.queryForObject("select sum(attempts) from outbox",Integer.class));
  reset(publisher);jdbc.update("update outbox set next_attempt_at=CURRENT_TIMESTAMP");
  assertTrue(relay.dispatchOne());assertTrue(relay.dispatchOne());assertFalse(relay.dispatchOne());
 }
}
