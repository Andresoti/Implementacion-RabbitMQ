package cl.duoc.pedidos360.testing;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
public final class LocalJwt implements AutoCloseable {
 public final RSAKey key;public final HttpServer server;public final String issuer;
 public LocalJwt(){try{
  key=new RSAKeyGenerator(2048).keyID("ephemeral").generate();server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  issuer="http://127.0.0.1:"+server.getAddress().getPort();
  server.createContext("/.well-known/jwks.json",exchange->{byte[] data=new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,data.length);try(var out=exchange.getResponseBody()){out.write(data);}});server.start();
 }catch(Exception e){throw new IllegalStateException(e);}}
 public JWTClaimsSet.Builder claims(String scope){return new JWTClaimsSet.Builder().issuer(issuer).subject("test-user").issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(300))).claim("token_use","access").claim("client_id","local-test-client").claim("scope",scope);}
 public String bearer(String scope)throws Exception{return token(claims(scope),key);}
 public String token(JWTClaimsSet.Builder claims,RSAKey signingKey)throws Exception{
  var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),claims.build());jwt.sign(new RSASSASigner(signingKey));return "Bearer "+jwt.serialize();
 }
 public void close(){server.stop(0);}
}
