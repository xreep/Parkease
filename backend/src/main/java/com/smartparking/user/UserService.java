package com.smartparking.user;

import com.smartparking.auth.AccountTokenService;
import com.smartparking.common.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class UserService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AccountTokenService accountTokens;

    @Transactional(readOnly = true)
    public UserDto get(Long id) {
        return UserDto.from(require(id));
    }

    public UserDto update(Long id, UpdateProfileRequest request) {
        User user = require(id);
        if (request.name() != null) {
            user.setName(request.name().trim());
        }
        if (request.phone() != null) {
            user.setPhone(request.phone().isBlank() ? null : request.phone());
        }
        return UserDto.from(user);
    }

    public void changePassword(Long id, ChangePasswordRequest request) {
        User user = require(id);
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw ApiException.badRequest("WRONG_PASSWORD", "Your current password is incorrect");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
    }

    public void resendVerification(Long id) {
        User user = require(id);
        if (user.isEmailVerified()) {
            throw ApiException.conflict("ALREADY_VERIFIED", "Your email is already verified");
        }
        accountTokens.sendEmailVerification(user);
    }

    public User require(Long id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("User not found"));
    }
}
