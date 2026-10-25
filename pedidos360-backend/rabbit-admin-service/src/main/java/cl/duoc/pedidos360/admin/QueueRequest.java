package cl.duoc.pedidos360.admin;
import jakarta.validation.constraints.*;
public record QueueRequest(@NotBlank @Pattern(regexp=ResourceName.PATTERN) String name,@NotNull Boolean durable){}
