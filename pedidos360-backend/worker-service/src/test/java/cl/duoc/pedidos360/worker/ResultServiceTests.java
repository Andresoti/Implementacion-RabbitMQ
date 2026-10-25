package cl.duoc.pedidos360.worker;
import cl.duoc.pedidos360.common.OrderEvent;
import java.time.Instant;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
class ResultServiceTests {
 JdbcTemplate jdbc;ResultService service;
 OrderEvent event=new OrderEvent(UUID.randomUUID().toString(),1,"Ana","Book",2,Instant.parse("2026-10-24T12:00:00Z"));
 java.sql.Connection schemaConnection;
 @AfterEach void closeSchemaConnection() throws Exception {schemaConnection.close();}
 @BeforeEach void setup() throws Exception {
  var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
  schemaConnection=ds.getConnection();
  new ResourceDatabasePopulator(new ClassPathResource("worker-schema.sql")).populate(schemaConnection);
  jdbc=new JdbcTemplate(ds);service=new ResultService(jdbc,new DataSourceTransactionManager(ds));
 }
 @Test void persistsActualResultsForThreeDomainsAndDeduplicatesRedelivery(){
  for(Domain domain:Domain.values()){service.process(event,domain);service.process(event,domain);}
  assertEquals(3,service.list().size());
  assertTrue(service.list().stream().anyMatch(r->r.domain()==Domain.TICKET&&r.result().startsWith("TKT-")));
  assertTrue(service.list().stream().anyMatch(r->r.domain()==Domain.DOCUMENT&&r.result().contains("Book")&&r.result().contains("2")));
  assertTrue(service.list().stream().anyMatch(r->r.domain()==Domain.NOTIFICATION&&r.result().contains("recorded")));
  assertEquals(1,jdbc.queryForObject("select count(*) from event_identity",Integer.class));
 }
 @Test void rejectsIdentityReuseWithChangedPayloadEvenAcrossDomains(){
  service.process(event,Domain.NOTIFICATION);
  var altered=new OrderEvent(event.eventId(),2,"Ana","Book",2,event.occurredAt());
  assertThrows(PermanentMessageException.class,()->service.process(altered,Domain.DOCUMENT));
  assertEquals(1,service.list().size());
 }
 @Test void concurrentDomainCreationAndRedeliveryRemainIdempotentAfterIdentityRace() throws Exception {
  var identityReads=new java.util.concurrent.CountDownLatch(3);
  var firstCommit=new java.util.concurrent.CountDownLatch(1);
  var ticketReads=new java.util.concurrent.CountDownLatch(2);
  var coordinated=org.mockito.Mockito.spy(jdbc);
  org.mockito.Mockito.doAnswer(call->{
   Object value=call.callRealMethod();
   if(((java.util.List<?>)value).isEmpty()){
    identityReads.countDown();
    assertTrue(identityReads.await(5,java.util.concurrent.TimeUnit.SECONDS));
    if(!Thread.currentThread().getName().equals("identity-winner"))assertTrue(firstCommit.await(5,java.util.concurrent.TimeUnit.SECONDS));
   }
   return value;
  }).when(coordinated).queryForList(org.mockito.ArgumentMatchers.eq("select fingerprint from event_identity where event_id=?"),org.mockito.ArgumentMatchers.eq(String.class),org.mockito.ArgumentMatchers.eq(event.eventId()));
  org.mockito.Mockito.doAnswer(call->{
   Object value=call.callRealMethod();
   if(call.getArgument(3).equals("TICKET") && value.equals(0)){
    ticketReads.countDown();assertTrue(ticketReads.await(5,java.util.concurrent.TimeUnit.SECONDS));
   }
   return value;
  }).when(coordinated).queryForObject(org.mockito.ArgumentMatchers.eq("select count(*) from processing_result where event_id=? and domain=?"),org.mockito.ArgumentMatchers.eq(Integer.class),org.mockito.ArgumentMatchers.eq(event.eventId()),org.mockito.ArgumentMatchers.anyString());
  var concurrentService=new ResultService(coordinated,new DataSourceTransactionManager(jdbc.getDataSource()));
  var executor=java.util.concurrent.Executors.newFixedThreadPool(3);
  try {
   var winner=executor.submit(()->{
    Thread.currentThread().setName("identity-winner");
    try{concurrentService.process(event,Domain.DOCUMENT);}finally{firstCommit.countDown();}
   });
   var first=executor.submit(()->concurrentService.process(event,Domain.TICKET));
   var second=executor.submit(()->concurrentService.process(event,Domain.TICKET));
   winner.get(10,java.util.concurrent.TimeUnit.SECONDS);
   first.get(10,java.util.concurrent.TimeUnit.SECONDS);second.get(10,java.util.concurrent.TimeUnit.SECONDS);
  } finally {executor.shutdownNow();}
  assertEquals(2,service.list().size());
 }
 @Test void concurrentRedeliveryCommitsOnlyOneResult() throws Exception {
  var executor=java.util.concurrent.Executors.newFixedThreadPool(8);
  try {
   var futures=new java.util.ArrayList<java.util.concurrent.Future<?>>();
   for(int i=0;i<20;i++)futures.add(executor.submit(()->service.process(event,Domain.TICKET)));
   for(var future:futures)future.get();
  } finally {executor.shutdownNow();}
  assertEquals(1,service.list().size());
 }
}
