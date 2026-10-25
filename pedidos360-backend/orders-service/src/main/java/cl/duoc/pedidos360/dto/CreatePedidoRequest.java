package cl.duoc.pedidos360.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CreatePedidoRequest(
        // PostgreSQL UTF-8 text cannot represent NUL or unpaired UTF-16 surrogates.
        @NotBlank @Size(max = 100)
        @jakarta.validation.constraints.Pattern(regexp = "[^\\x00\\x{D800}-\\x{DFFF}]*") String cliente,
        @NotBlank @Size(max = 200)
        @jakarta.validation.constraints.Pattern(regexp = "[^\\x00\\x{D800}-\\x{DFFF}]*") String producto,
        @NotNull @Min(1)
        @Max(10000) Integer cantidad) {}
