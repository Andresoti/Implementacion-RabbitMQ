package cl.duoc.pedidos360.worker;
import jakarta.persistence.*;
@Entity @Table(name="event_identity")
class EventIdentityEntity {
 @Id @Column(name="event_id",length=36) String eventId;
 @Column(nullable=false,length=64) String fingerprint;
 protected EventIdentityEntity(){}
}
