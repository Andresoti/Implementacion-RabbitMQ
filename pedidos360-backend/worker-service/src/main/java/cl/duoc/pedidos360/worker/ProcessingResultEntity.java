package cl.duoc.pedidos360.worker;
import jakarta.persistence.*;
import java.time.Instant;
@Entity @Table(name="processing_result") @IdClass(ProcessingResultKey.class)
class ProcessingResultEntity {
 @Id @Column(name="event_id",length=36) String eventId;
 @Id @Column(length=20) String domain;
 @Column(name="order_id",nullable=false) long orderId;
 @Column(nullable=false,columnDefinition="text") String result;
 @Column(name="processed_at",nullable=false) Instant processedAt;
 protected ProcessingResultEntity(){}
}
