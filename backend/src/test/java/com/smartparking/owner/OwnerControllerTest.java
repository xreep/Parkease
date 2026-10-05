package com.smartparking.owner;

import static com.smartparking.support.OwnerTestSupport.gif;
import static com.smartparking.support.OwnerTestSupport.pdf;
import static com.smartparking.support.OwnerTestSupport.unverifiedOwner;
import static com.smartparking.support.OwnerTestSupport.verifiedOwner;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartparking.support.AuthTestSupport;
import com.smartparking.support.IntegrationTest;
import com.smartparking.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class OwnerControllerTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired OwnerProfileRepository profiles;

    @Test
    void newOwnerProfileIsUnsubmitted() throws Exception {
        String auth = unverifiedOwner(mvc, "o1@example.com");
        mvc.perform(get("/api/v1/owner/profile").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationStatus").value("UNSUBMITTED"))
                .andExpect(jsonPath("$.documentType").isEmpty())
                .andExpect(jsonPath("$.hasDocument").value(false));
    }

    @Test
    void driversCannotUseOwnerEndpoints() throws Exception {
        String driver = AuthTestSupport.bearer(AuthTestSupport.accessToken(
                AuthTestSupport.register(mvc, "d1@example.com", "DRIVER")));
        mvc.perform(get("/api/v1/owner/profile").header(HttpHeaders.AUTHORIZATION, driver))
                .andExpect(status().isForbidden());
    }

    @Test
    void savesUpiPayoutAndMasksBankAccount() throws Exception {
        String auth = unverifiedOwner(mvc, "o2@example.com");
        mvc.perform(put("/api/v1/owner/profile/payout").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"upiId":"ravi.k@okaxis","bankAccount":"123456789012","ifsc":"HDFC0001234","accountName":"Ravi Kumar"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payoutUpi").value("ravi.k@okaxis"))
                .andExpect(jsonPath("$.payoutBankAccountLast4").value("9012"))
                .andExpect(jsonPath("$.payoutIfsc").value("HDFC0001234"));
    }

    private static final String FULL_PAYOUT = """
            {"upiId":"ravi.k@okaxis","bankAccount":"123456789012","ifsc":"HDFC0001234","accountName":"Ravi Kumar"}""";

    @Test
    void payoutWithoutBankAccountKeyKeepsStoredAccount() throws Exception {
        String auth = unverifiedOwner(mvc, "o2a@example.com");
        savePayout(auth, FULL_PAYOUT).andExpect(status().isOk());
        savePayout(auth, """
                {"upiId":"ravi.new@okaxis","ifsc":"HDFC0001234","accountName":"Ravi Kumar"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payoutUpi").value("ravi.new@okaxis"))
                .andExpect(jsonPath("$.payoutBankAccountLast4").value("9012"));
    }

    @Test
    void blankBankAccountClearsStoredAccount() throws Exception {
        String auth = unverifiedOwner(mvc, "o2b@example.com");
        savePayout(auth, FULL_PAYOUT).andExpect(status().isOk());
        savePayout(auth, """
                {"upiId":"ravi.k@okaxis","bankAccount":"","ifsc":"","accountName":"Ravi Kumar"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payoutBankAccountLast4").isEmpty());
    }

    @Test
    void storedAccountWithIfscSatisfiesBankOnlyPayout() throws Exception {
        String auth = unverifiedOwner(mvc, "o2c@example.com");
        savePayout(auth, """
                {"bankAccount":"123456789012","ifsc":"HDFC0001234","accountName":"Ravi Kumar"}""")
                .andExpect(status().isOk());
        savePayout(auth, """
                {"ifsc":"HDFC0001234","accountName":"Ravi K"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payoutAccountName").value("Ravi K"))
                .andExpect(jsonPath("$.payoutBankAccountLast4").value("9012"));
    }

    private org.springframework.test.web.servlet.ResultActions savePayout(String auth, String body) throws Exception {
        return mvc.perform(put("/api/v1/owner/profile/payout").header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void payoutNeedsUpiOrBankWithIfsc() throws Exception {
        String auth = unverifiedOwner(mvc, "o3@example.com");
        mvc.perform(put("/api/v1/owner/profile/payout").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bankAccount":"123456789012","accountName":"Ravi Kumar"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAYOUT_DETAILS_REQUIRED"));
        mvc.perform(put("/api/v1/owner/profile/payout").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"upiId":"not a upi","accountName":"Ravi"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("upiId"));
    }

    @Test
    void uploadingDocumentMovesToPendingAndGivesSignedUrl() throws Exception {
        String auth = unverifiedOwner(mvc, "o4@example.com");
        mvc.perform(multipart("/api/v1/owner/verification").file(pdf())
                        .param("documentType", "AADHAAR").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationStatus").value("PENDING"))
                .andExpect(jsonPath("$.documentType").value("AADHAAR"))
                .andExpect(jsonPath("$.hasDocument").value(true))
                .andExpect(jsonPath("$.documentSubmittedAt").isNotEmpty());
        mvc.perform(get("/api/v1/owner/verification/document-url").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(org.hamcrest.Matchers.containsString("/api/v1/files/private?key=local%2Fprivate%2Fowner-documents%2F")))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }

    @Test
    void rejectsDisguisedFileTypes() throws Exception {
        String auth = unverifiedOwner(mvc, "o5@example.com");
        mvc.perform(multipart("/api/v1/owner/verification").file(gif())
                        .param("documentType", "PAN").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_FILE_TYPE"));
    }

    @Test
    void verifiedOwnerCannotResubmit() throws Exception {
        String auth = verifiedOwner(mvc, users, profiles, "o6@example.com");
        mvc.perform(multipart("/api/v1/owner/verification").file(pdf())
                        .param("documentType", "PAN").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_VERIFIED"));
    }

    @Test
    void documentUrlIs404WithoutDocument() throws Exception {
        String auth = unverifiedOwner(mvc, "o7@example.com");
        mvc.perform(get("/api/v1/owner/verification/document-url").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isNotFound());
    }

    @Test
    void documentFromAnotherStorageBackendIs404NotFound() throws Exception {
        String auth = unverifiedOwner(mvc, "o8@example.com");
        Long id = users.findByEmail("o8@example.com").orElseThrow().getId();
        OwnerProfile profile = profiles.findById(id).orElseThrow();
        profile.setDocumentKey("cloudinary/private/parkease/owner-documents/abc.pdf");
        profiles.saveAndFlush(profile);
        mvc.perform(get("/api/v1/owner/verification/document-url").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("Document not available"));
    }
}
