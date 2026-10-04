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
                .andExpect(jsonPath("$.documentType").isEmpty());
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
}
