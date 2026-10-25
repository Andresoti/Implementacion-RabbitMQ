package cl.duoc.pedidos360.worker;
import java.time.Instant;
public record ProcessingResult(String eventId,long orderId,Domain domain,String result,Instant processedAt){}
