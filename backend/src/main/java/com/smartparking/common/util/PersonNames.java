package com.smartparking.common.util;

/** Display forms of a person's name that reveal as little as possible. */
public final class PersonNames {

    private PersonNames() {
    }

    /** First name and the initial of the last name: "Rahul Sharma" becomes "Rahul S."; a single name stays as is. */
    public static String firstNameLastInitial(String fullName) {
        String[] parts = fullName == null ? new String[0] : fullName.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return "Driver";
        }
        if (parts.length == 1) {
            return parts[0];
        }
        String last = parts[parts.length - 1];
        return parts[0] + " " + last.substring(0, last.offsetByCodePoints(0, 1)).toUpperCase() + ".";
    }
}
