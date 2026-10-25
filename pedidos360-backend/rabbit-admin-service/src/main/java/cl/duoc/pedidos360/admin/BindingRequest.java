package cl.duoc.pedidos360.admin;
import jakarta.validation.constraints.*;
public record BindingRequest(@NotBlank @Pattern(regexp=ResourceName.PATTERN) String exchange,@NotBlank @Pattern(regexp=ResourceName.PATTERN) String queue,@NotNull @Size(max=255) @Pattern(regexp="[^\\x00\\x{D800}-\\x{DFFF}]*") String routingKey){
 @AssertTrue @com.fasterxml.jackson.annotation.JsonIgnore
 public boolean isRoutingKeyWithinByteLimit(){
  return routingKey==null || routingKey.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=255;
 }
}
