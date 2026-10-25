package cl.duoc.pedidos360.worker;
import cl.duoc.pedidos360.common.OrderEvent;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.*;
import org.springframework.dao.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class DeliveryHandlerTests {
 static final String ID="bfbb962b-e265-4dd4-af13-26bc35d6471b";
 static final String BODY="{\"eventId\":\""+ID+"\",\"orderId\":1,\"customer\":\"Ana\",\"product\":\"Book\",\"quantity\":2,\"occurredAt\":\"2026-10-24T12:00:00Z\"}";
 ResultService results=mock(ResultService.class);Channel channel=mock(Channel.class);
 DeliveryHandler handler=new DeliveryHandler(new EventDecoder(JsonMapper.builder().build()),results,3,0);
 static Message message(String body){var p=new MessageProperties();p.setMessageId(ID);p.setDeliveryTag(42);p.setContentType("application/json");return new Message(body.getBytes(StandardCharsets.UTF_8),p);}
 @Test void commitsResultBeforeExplicitSingleAck() throws Exception {
  handler.handle(message(BODY),channel,Domain.DOCUMENT);
  var order=inOrder(results,channel);order.verify(results).process(any(OrderEvent.class),eq(Domain.DOCUMENT));order.verify(channel).basicAck(42,false);
  verify(channel,never()).basicNack(anyLong(),anyBoolean(),anyBoolean());
 }
 @Test void transientFailureRetriesBoundedlyThenAcks() throws Exception {
  doThrow(new TransientDataAccessResourceException("temporary")).doNothing().when(results).process(any(),any());
  handler.handle(message(BODY),channel,Domain.TICKET);
  verify(results,times(2)).process(any(),eq(Domain.TICKET));verify(channel).basicAck(42,false);
 }
 @Test void exhaustedTransientFailureNacksWithoutRequeue() throws Exception {
  doThrow(new DataAccessResourceFailureException("offline")).when(results).process(any(),any());
  handler.handle(message(BODY),channel,Domain.NOTIFICATION);
  verify(results,times(3)).process(any(),any());verify(channel).basicNack(42,false,false);verify(channel,never()).basicAck(anyLong(),anyBoolean());
 }
 @ParameterizedTest @ValueSource(strings={"55P03","40001","40P01"})
 void postgresTransientSqlStatesRetryBeforeDeadLettering(String sqlState) throws Exception {
  var failure=new org.springframework.jdbc.UncategorizedSQLException("process event","select count(*)",
   new java.sql.SQLException("database details must not be logged",sqlState));
  doThrow(failure).when(results).process(any(),any());
  handler.handle(message(BODY),channel,Domain.TICKET);
  verify(results,times(3)).process(any(),eq(Domain.TICKET));
  verify(channel).basicNack(42,false,false);verify(channel,never()).basicAck(anyLong(),anyBoolean());
 }
 @Test void permanentSqlStateIsNotRetried() throws Exception {
  var failure=new org.springframework.jdbc.UncategorizedSQLException("process event","select count(*)",
   new java.sql.SQLException("invalid SQL","42601"));
  doThrow(failure).when(results).process(any(),any());
  handler.handle(message(BODY),channel,Domain.TICKET);
  verify(results).process(any(),eq(Domain.TICKET));verify(channel).basicNack(42,false,false);
 }
 @Test void permanentFailureIsNotRetried() throws Exception {
  doThrow(new PermanentMessageException()).when(results).process(any(),any());
  handler.handle(message(BODY),channel,Domain.TICKET);
  verify(results).process(any(),any());verify(channel).basicNack(42,false,false);
 }
 @ParameterizedTest @ValueSource(strings={"{","null","[]","{}","quantityZero","quantityFloat","quantityString","quantityOverflow","orderZero","orderFloat","customerBlank","customerLong","productLong","badTime","badId","missingId","wrongIdentity","wrongContentType","oversize","typeHeader","extraField","duplicateField","trailingJson"})
 void malformedPayloadIsDeadLetteredWithoutBusinessProcessing(String kind) throws Exception {
  String body=switch(kind){
   case "quantityZero"->BODY.replace("\"quantity\":2","\"quantity\":0");
   case "quantityFloat"->BODY.replace("\"quantity\":2","\"quantity\":1.5");
   case "quantityString"->BODY.replace("\"quantity\":2","\"quantity\":\"2\"");
   case "quantityOverflow"->BODY.replace("\"quantity\":2","\"quantity\":2147483648");
   case "orderZero"->BODY.replace("\"orderId\":1","\"orderId\":0");
   case "orderFloat"->BODY.replace("\"orderId\":1","\"orderId\":1.2");
   case "customerBlank"->BODY.replace("Ana"," ");
   case "customerLong"->BODY.replace("Ana","A".repeat(101));
   case "productLong"->BODY.replace("Book","B".repeat(201));
   case "badTime"->BODY.replace("2026-10-24T12:00:00Z","yesterday");
   case "badId"->BODY.replace(ID,"1-1-1-1-1");
   case "missingId","wrongIdentity","wrongContentType","typeHeader"->BODY;
   case "oversize"->" ".repeat(17000)+BODY;
   case "duplicateField"->BODY.replace("\"quantity\":2","\"quantity\":1,\"quantity\":2");
   case "trailingJson"->BODY+"{}";
   case "extraField"->BODY.replace("\"orderId\"","\"secret\":true,\"orderId\"");
   default->kind;
  };
  var msg=message(body);
  if(kind.equals("missingId"))msg.getMessageProperties().setMessageId(null);
  if(kind.equals("wrongIdentity"))msg.getMessageProperties().setMessageId("wrong");
  if(kind.equals("wrongContentType"))msg.getMessageProperties().setContentType("application/x-java-serialized-object");
  if(kind.equals("typeHeader"))msg.getMessageProperties().setHeader("__TypeId__","java.lang.Runtime");
  if(kind.equals("typeHeader")){
   // Untrusted converter metadata must never select a Java type.
   handler.handle(msg,channel,Domain.DOCUMENT);verify(channel).basicAck(42,false);
  } else {
   handler.handle(msg,channel,Domain.DOCUMENT);verify(channel).basicNack(42,false,false);verifyNoInteractions(results);
  }
 }
 @ParameterizedTest
 @org.junit.jupiter.params.provider.MethodSource("unsupportedTextMessages")
 void unsupportedTextIsDeadLetteredBeforeBusinessProcessing(String body) throws Exception {
  handler.handle(message(body),channel,Domain.DOCUMENT);
  verify(channel).basicNack(42,false,false);verifyNoInteractions(results);
 }
 static java.util.stream.Stream<String> unsupportedTextMessages(){
  return java.util.stream.Stream.of("Ana","Book").flatMap(value->
   java.util.stream.Stream.of("\\u0000","\\uD800","\\uDC00").map(escape->BODY.replace(value,"prefix"+escape+"suffix")));
 }
 @Test void validSupplementaryUnicodeIsPreserved() throws Exception {
  handler.handle(message(BODY.replace("Ana","Ana \\uD83D\\uDE00")),channel,Domain.DOCUMENT);
  verify(results).process(argThat(event->event.customer().equals("Ana 😀")),eq(Domain.DOCUMENT));
  verify(channel).basicAck(42,false);
 }
 @Test void ackTransportFailureIsNotFollowedBySecondSettlement() throws Exception {
  doThrow(new java.io.IOException("closed")).when(channel).basicAck(42,false);
  assertThrows(java.io.IOException.class,()->handler.handle(message(BODY),channel,Domain.DOCUMENT));
  verify(channel,never()).basicNack(anyLong(),anyBoolean(),anyBoolean());
 }
}
