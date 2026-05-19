package com.jaipilot.cli.auth;

public record TokenInfo(
        String accessToken,
        String refreshToken,
        long expiresAtEpochSeconds,
        String email
) {

    public boolean isExpired(long skewSeconds) {
        if (expiresAtEpochSeconds <= 0) {
            return true;
        }
        long now = System.currentTimeMillis() / 1_000L;
        return expiresAtEpochSeconds - skewSeconds <= now;
    }
}
