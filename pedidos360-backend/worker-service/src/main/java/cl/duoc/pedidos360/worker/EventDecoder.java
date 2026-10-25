package cl.duoc.pedidos360.worker;
import cl.duoc.pedidos360.common.OrderEvent;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;
@Component
public class EventDecoder {
 private final ObjectMapper mapper;
 private static final Set<String> FIELDS=Set.of("eventId","orderId","customer","product","quantity","occurredAt");
 public EventDecoder(ObjectMapper mapper){this.mapper=mapper.rebuild().enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();}
 public OrderEvent decode(Message message){
  try {
   if(message.getBody().length>16384 || !"application/json".equalsIgnoreCase(message.getMessageProperties().getContentType().split(";")[0].trim()))throw new PermanentMessageException();
   var node=mapper.readTree(message.getBody());
   if(node==null || !node.isObject() || !new HashSet<>(node.propertyNames()).equals(FIELDS))throw new PermanentMessageException();
   String id=text(node,"eventId",36);if(!UUID.fromString(id).toString().equals(id) || !id.equals(message.getMessageProperties().getMessageId()))throw new PermanentMessageException();
   var order=node.get("orderId");var quantity=node.get("quantity");
   if(!order.isIntegralNumber() || !order.canConvertToLong() || order.longValue()<=0 || !quantity.isIntegralNumber() || !quantity.canConvertToInt() || quantity.intValue()<1 || quantity.intValue()>10000)throw new PermanentMessageException();
   return new OrderEvent(id,order.longValue(),text(node,"customer",100),text(node,"product",200),quantity.intValue(),Instant.parse(text(node,"occurredAt",64)));
  } catch(RuntimeException invalid){throw new PermanentMessageException();}
 }
 private static String text(JsonNode node,String field,int max){
  var value=node.get(field);if(value==null || !value.isString() || value.stringValue().isBlank() || value.stringValue().length()>max
   || !value.stringValue().matches("[^\\x00\\x{D800}-\\x{DFFF}]*"))throw new PermanentMessageException();
  return value.stringValue();
 }
}
