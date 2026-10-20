package com.apismartpulse.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.KeyType;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.MappedJwtClaimSetConverter;

/**
 * Verifies Neon Auth access tokens: EdDSA (Ed25519) signatures from the branch JWKS, issuer and
 * audience = the Neon Auth origin, 15-minute expiry. Spring Security has no EdDSA support and
 * Nimbus needs Tink for it, so the signature check uses the JDK's Ed25519 directly; Nimbus only
 * parses the token and fetches/caches the JWKS (refreshing it when an unknown key id appears).
 */
public final class NeonJwtDecoder implements JwtDecoder {
  // DER prefix of an X.509 SubjectPublicKeyInfo for a raw 32-byte Ed25519 key (RFC 8410).
  private static final byte[] ED25519_SPKI_PREFIX = {
    0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
  };
  private static final List<JWSAlgorithm> ALGORITHMS =
      List.of(JWSAlgorithm.EdDSA, JWSAlgorithm.Ed25519);

  private final JWKSource<SecurityContext> keys;
  private final OAuth2TokenValidator<Jwt> validator;
  private final MappedJwtClaimSetConverter claims =
      MappedJwtClaimSetConverter.withDefaults(Collections.emptyMap());

  public NeonJwtDecoder(String neonAuthUrl, Clock clock) {
    try {
      URI base = URI.create(neonAuthUrl.replaceAll("/+$", ""));
      URL jwks = URI.create(base + "/.well-known/jwks.json").toURL();
      this.keys =
          JWKSourceBuilder.create(jwks, new DefaultResourceRetriever(3_000, 3_000, 64 * 1024))
              .build();
      String origin = base.getScheme() + "://" + base.getAuthority();
      JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ofSeconds(30));
      timestamps.setClock(clock);
      this.validator =
          new DelegatingOAuth2TokenValidator<>(
              timestamps,
              new JwtIssuerValidator(origin),
              new JwtClaimValidator<List<String>>(
                  "aud", aud -> aud != null && aud.contains(origin)),
              new JwtClaimValidator<String>("sub", sub -> sub != null && !sub.isBlank()));
    } catch (java.net.MalformedURLException e) {
      throw new IllegalStateException("NEON_AUTH_URL is not a valid URL", e);
    }
  }

  @Override
  public Jwt decode(String token) throws JwtException {
    SignedJWT jwt;
    try {
      jwt = SignedJWT.parse(token);
    } catch (java.text.ParseException e) {
      throw new BadJwtException("Malformed token", e);
    }
    if (!ALGORITHMS.contains(jwt.getHeader().getAlgorithm())) {
      throw new BadJwtException("Unsupported signing algorithm");
    }
    verifySignature(jwt, publicKey(jwt.getHeader().getKeyID()));

    Map<String, Object> claimSet;
    try {
      claimSet = claims.convert(jwt.getJWTClaimsSet().toJSONObject());
    } catch (java.text.ParseException e) {
      throw new BadJwtException("Malformed claims", e);
    }
    Jwt result =
        Jwt.withTokenValue(token)
            .headers(h -> h.putAll(jwt.getHeader().toJSONObject()))
            .claims(c -> c.putAll(claimSet))
            .build();
    OAuth2TokenValidatorResult outcome = validator.validate(result);
    if (outcome.hasErrors()) {
      throw new JwtValidationException("Token rejected", outcome.getErrors());
    }
    return result;
  }

  private PublicKey publicKey(String kid) {
    if (kid == null) throw new BadJwtException("Token has no key id");
    List<JWK> found;
    try {
      JWKMatcher matcher = new JWKMatcher.Builder().keyID(kid).keyType(KeyType.OKP).build();
      found = keys.get(new JWKSelector(matcher), null);
    } catch (Exception e) {
      // Provider unreachable: not the caller's fault, but still no authentication.
      throw new JwtException("Could not load signing keys", e);
    }
    if (found.isEmpty()) throw new BadJwtException("Unknown signing key");
    OctetKeyPair okp = (OctetKeyPair) found.get(0);
    if (!Curve.Ed25519.equals(okp.getCurve())) throw new BadJwtException("Unsupported key curve");
    byte[] raw = okp.getX().decode();
    byte[] spki = new byte[ED25519_SPKI_PREFIX.length + raw.length];
    System.arraycopy(ED25519_SPKI_PREFIX, 0, spki, 0, ED25519_SPKI_PREFIX.length);
    System.arraycopy(raw, 0, spki, ED25519_SPKI_PREFIX.length, raw.length);
    try {
      return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
    } catch (GeneralSecurityException e) {
      throw new BadJwtException("Invalid signing key", e);
    }
  }

  private static void verifySignature(SignedJWT jwt, PublicKey key) {
    try {
      Signature verifier = Signature.getInstance("Ed25519");
      verifier.initVerify(key);
      verifier.update(jwt.getSigningInput());
      if (!verifier.verify(jwt.getSignature().decode())) {
        throw new BadJwtException("Invalid signature");
      }
    } catch (GeneralSecurityException e) {
      throw new BadJwtException("Invalid signature", e);
    }
  }
}
