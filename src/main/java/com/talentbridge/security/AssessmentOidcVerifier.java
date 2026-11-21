package com.talentbridge.security;

import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.json.webtoken.JsonWebSignature;
import com.google.auth.oauth2.TokenVerifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.time.Clock;

@Component
public class AssessmentOidcVerifier {
    private final String audience, caller;
    private final Clock clock;
    private final TokenVerifier verifier;

    @Autowired
    public AssessmentOidcVerifier(@Value("${assessment.tasks.audience:}") String audience,
            @Value("${assessment.tasks.caller-email:}") String caller, Clock clock) {
        this(audience, caller, clock, TokenVerifier.newBuilder().setAudience(audience)
                .setIssuer("https://accounts.google.com").setClock(() -> clock.millis())
                .setHttpTransportFactory(GoogleCertificateTransport::new).build());
    }

    AssessmentOidcVerifier(String audience, String caller, Clock clock, TokenVerifier verifier) {
        this.audience = audience; this.caller = caller; this.clock = clock; this.verifier = verifier;
    }

    public boolean verify(String token) {
        if (audience.isBlank() || caller.isBlank() || token == null || token.length() > 16384) return false;
        try {
            // TokenVerifier also accepts missing expiry and ES256; this endpoint must not.
            var parsed = JsonWebSignature.parse(GsonFactory.getDefaultInstance(), token);
            var claims = parsed.getPayload();
            if (!"RS256".equals(parsed.getHeader().getAlgorithm()) || claims.getExpirationTimeSeconds() == null
                    || claims.getExpirationTimeSeconds() <= clock.instant().getEpochSecond()
                    || !"https://accounts.google.com".equals(claims.getIssuer())
                    || !audience.equals(claims.getAudience()) || !caller.equals(claims.get("email"))
                    || !Boolean.TRUE.equals(claims.get("email_verified"))) return false;
            // Official Google verifier checks the signature using Google's cached public keys, without ADC.
            // https://github.com/googleapis/google-auth-library-java/blob/main/oauth2_http/java/com/google/auth/oauth2/TokenVerifier.java
            verifier.verify(token);
            return true;
        } catch (Exception rejected) { return false; }
    }
}
