package cl.duoc.pedidos360.worker;
import cl.duoc.pedidos360.common.OrderEvent;
import org.springframework.amqp.core.Message;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.*;
import org.springframework.transaction.CannotCreateTransactionException;
import org.slf4j.Logger;import org.slf4j.LoggerFactory;
@Component
public class DeliveryHandler {
 private static final Logger log=LoggerFactory.getLogger(DeliveryHandler.class);
 private final EventDecoder decoder;private final ResultService results;private final int attempts;private final long retryDelay;
 public DeliveryHandler(EventDecoder decoder,ResultService results,@Value("${app.worker.max-attempts:3}") int attempts,@Value("${app.worker.retry-delay-ms:100}") long retryDelay){
  if(attempts<1 || attempts>10 || retryDelay<0 || retryDelay>5000)throw new IllegalArgumentException("Invalid retry bounds");
  this.decoder=decoder;this.results=results;this.attempts=attempts;this.retryDelay=retryDelay;
 }
 public void handle(Message message,Channel channel,Domain domain) throws IOException {
  long tag=message.getMessageProperties().getDeliveryTag();
  OrderEvent event;
  try{event=decoder.decode(message);}catch(PermanentMessageException invalid){deadLetter(channel,tag,domain,"invalid_event");return;}
  for(int attempt=1;attempt<=attempts;attempt++){
   try{results.process(event,domain);}
   catch(RuntimeException failure){
    boolean transientFailure=failure instanceof TransientDataAccessException || failure instanceof RecoverableDataAccessException || failure instanceof DataAccessResourceFailureException || failure instanceof CannotCreateTransactionException || hasTransientSqlState(failure);
    if(transientFailure && attempt<attempts){
     log.warn("Consumer retry: domain={} deliveryTag={} attempt={}",domain,tag,attempt);
     try{Thread.sleep(retryDelay*attempt);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();deadLetter(channel,tag,domain,"interrupted");return;}
     continue;
    }
    deadLetter(channel,tag,domain,transientFailure?"transient_exhausted":"permanent_failure");return;
   }
   // The service transaction has committed. Transport failures escape, without double settlement.
   channel.basicAck(tag,false);return;
  }
 }
 private static boolean hasTransientSqlState(Throwable failure){
  // PostgreSQL lock/serialization failures may be left uncategorized by Spring.
  // Inspect only known retryable SQL states; never retry arbitrary SQL errors.
  for(int depth=0;failure!=null && depth<16;depth++,failure=failure.getCause()){
   if(failure instanceof java.sql.SQLException sql &&
      java.util.Set.of("55P03","40001","40P01").contains(sql.getSQLState()==null?"":sql.getSQLState()))return true;
  }
  return false;
 }
 private void deadLetter(Channel channel,long tag,Domain domain,String category)throws IOException{
  log.warn("Consumer dead-letter: domain={} deliveryTag={} category={}",domain,tag,category);
  channel.basicNack(tag,false,false);
 }
}
