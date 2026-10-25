package cl.duoc.pedidos360.worker;
import cl.duoc.pedidos360.common.OrderEvent;
import java.time.Instant;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.security.MessageDigest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
@Service
public class ResultService {
 private final JdbcTemplate jdbc; private final TransactionTemplate tx;
 public ResultService(JdbcTemplate jdbc,PlatformTransactionManager manager){
  this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);
  tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
 }
 public void process(OrderEvent event,Domain domain){
  // Two independent uniqueness races are possible: identity, then domain result.
  // Each failed transaction must roll back before reading the winner's commit.
  for(int attempt=0;attempt<3;attempt++){
   try {tx.executeWithoutResult(status->persist(event,domain));return;}
   catch(DuplicateKeyException race){if(attempt==2)throw race;}
  }
 }
 private void persist(OrderEvent event,Domain domain){
  String fingerprint=fingerprint(event);
  var identities=jdbc.queryForList("select fingerprint from event_identity where event_id=?",String.class,event.eventId());
  if(identities.isEmpty())jdbc.update("insert into event_identity(event_id,fingerprint) values(?,?)",event.eventId(),fingerprint);
  else if(!identities.get(0).equals(fingerprint))throw new PermanentMessageException();
  if(jdbc.queryForObject("select count(*) from processing_result where event_id=? and domain=?",Integer.class,event.eventId(),domain.name())>0)return;
  String result=switch(domain){
   case NOTIFICATION -> "Notification recorded for order "+event.orderId()+" (no email sent)";
   case TICKET -> "TKT-"+UUID.nameUUIDFromBytes((event.eventId()+":TICKET").getBytes(StandardCharsets.UTF_8));
   case DOCUMENT -> "Receipt for order "+event.orderId()+" | Product: "+event.product()+" | Quantity: "+event.quantity();
  };
  jdbc.update("insert into processing_result(event_id,order_id,domain,result,processed_at) values(?,?,?,?,?)",event.eventId(),event.orderId(),domain.name(),result,Timestamp.from(Instant.now()));
 }
 private static String fingerprint(OrderEvent event){
  // Length framing avoids delimiter ambiguities in customer/product strings.
  String value=event.orderId()+":"+event.customer().length()+":"+event.customer()+event.product().length()+":"+event.product()+":"+event.quantity()+":"+event.occurredAt();
  try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
  catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256 unavailable");}
 }
 public List<ProcessingResult> list(){
  return jdbc.query("select event_id,order_id,domain,result,processed_at from processing_result order by processed_at,event_id,domain",
   (rs,n)->new ProcessingResult(rs.getString(1),rs.getLong(2),Domain.valueOf(rs.getString(3)),rs.getString(4),rs.getTimestamp(5).toInstant()));
 }
}
