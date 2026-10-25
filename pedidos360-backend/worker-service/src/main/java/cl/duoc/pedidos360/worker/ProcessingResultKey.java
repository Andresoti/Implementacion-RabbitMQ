package cl.duoc.pedidos360.worker;
import java.io.Serializable;
import java.util.Objects;
public class ProcessingResultKey implements Serializable {
 public String eventId;public String domain;
 public ProcessingResultKey(){}
 @Override public boolean equals(Object other){return other instanceof ProcessingResultKey key&&Objects.equals(eventId,key.eventId)&&Objects.equals(domain,key.domain);}
 @Override public int hashCode(){return Objects.hash(eventId,domain);}
}
