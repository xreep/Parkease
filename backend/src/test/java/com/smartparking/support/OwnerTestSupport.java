package com.smartparking.support;

import com.smartparking.owner.OwnerProfile;
import com.smartparking.owner.OwnerProfileRepository;
import com.smartparking.owner.VerificationStatus;
import com.smartparking.user.User;
import com.smartparking.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

public final class OwnerTestSupport {

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    private OwnerTestSupport() {
    }

    public static MockMultipartFile png(String name) {
        return new MockMultipartFile("file", name, "image/png", concat(PNG_MAGIC, new byte[16]));
    }

    public static MockMultipartFile pdf() {
        return new MockMultipartFile("file", "doc.pdf", "application/pdf",
                "%PDF-1.4\n%%EOF".getBytes(StandardCharsets.US_ASCII));
    }

    /** GIF bytes declared as PNG, to prove the server sniffs content rather than trusting the header. */
    public static MockMultipartFile gif() {
        return new MockMultipartFile("file", "fake.png", "image/png",
                concat("GIF89a".getBytes(StandardCharsets.US_ASCII), new byte[16]));
    }

    /** Registers an OWNER and returns {@code "Bearer <access>"}. */
    public static String unverifiedOwner(MockMvc mvc, String email) throws Exception {
        return AuthTestSupport.bearer(AuthTestSupport.accessToken(AuthTestSupport.register(mvc, email, "OWNER")));
    }

    /** Registers an OWNER, marks the profile VERIFIED through the repository, returns the bearer header. */
    public static String verifiedOwner(MockMvc mvc, UserRepository users, OwnerProfileRepository profiles,
                                       String email) throws Exception {
        String bearer = unverifiedOwner(mvc, email);
        User user = users.findByEmail(email).orElseThrow();
        OwnerProfile profile = profiles.findById(user.getId()).orElseThrow();
        profile.setVerificationStatus(VerificationStatus.VERIFIED);
        profile.setVerifiedAt(Instant.now());
        profiles.saveAndFlush(profile);
        return bearer;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
