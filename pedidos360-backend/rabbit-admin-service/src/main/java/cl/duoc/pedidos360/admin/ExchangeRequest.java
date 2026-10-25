package cl.duoc.pedidos360.admin;
import jakarta.validation.constraints.*;
public record ExchangeRequest(@NotBlank @Pattern(regexp=ResourceName.PATTERN) String name,@NotNull Type type,@NotNull Boolean durable){
 public enum Type {DIRECT,TOPIC}
}
