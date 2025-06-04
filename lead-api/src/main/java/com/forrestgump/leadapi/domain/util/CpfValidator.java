package com.forrestgump.leadapi.domain.util;

public final class CpfValidator {

    private CpfValidator() {
    }

    public static boolean isValidCpf(String cpf) {
        if (cpf == null || cpf.length() != 11 || !cpf.matches("\\d{11}")) {
            return false;
        }

        int[] digits = cpf.chars().map(c -> c - '0').toArray();

        int sum = 0;
        for (int i = 0; i < 9; i++) {
            sum += digits[i] * (10 - i);
        }
        int firstDigit = (sum * 10) % 11;
        if (firstDigit == 10) firstDigit = 0;
        if (firstDigit != digits[9]) return false;

        sum = 0;
        for (int i = 0; i < 10; i++) {
            sum += digits[i] * (11 - i);
        }
        int secondDigit = (sum * 10) % 11;
        if (secondDigit == 10) secondDigit = 0;
        return secondDigit == digits[10];
    }
}