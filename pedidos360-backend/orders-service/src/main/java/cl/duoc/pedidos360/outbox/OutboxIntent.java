package cl.duoc.pedidos360.outbox;
import jakarta.persistence.*;
import java.time.Instant;
/** Schema validation mapping. Outbox writes and row locks use explicit JDBC. */
@Entity @Table(name="outbox",uniqueConstraints=@UniqueConstraint(columnNames={"event_id","destination"}))
class OutboxIntent {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
 @Column(name="event_id",nullable=false,length=36) String eventId;
 @Column(nullable=false,length=10) String destination;
 @Column(nullable=false,columnDefinition="text") String payload;
 @Column(nullable=false) int attempts;
 @Column(name="next_attempt_at",nullable=false) Instant nextAttemptAt;
 @Column(name="published_at") Instant publishedAt;
 protected OutboxIntent(){}
}
