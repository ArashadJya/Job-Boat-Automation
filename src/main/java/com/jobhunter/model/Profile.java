package com.jobhunter.model;

import java.util.List;

/**
 * Immutable snapshot of everything extracted from a candidate's resume.
 *
 * @param file       the resume file name (e.g. "john_doe.pdf")
 * @param name       candidate's full name
 * @param email      candidate's email address (may be empty if not found)
 * @param phone      candidate's phone number (may be empty if not found)
 * @param skills     canonicalized list of matched skills, in resume order
 * @param targetRole best-guess target role, e.g. "Java Developer"
 */
public record Profile(String file, String name, String email,
                       String phone, List<String> skills, String targetRole) {

    /** Convenience: top N skills, safe against short lists. */
    public List<String> topSkills(int n) {
        return skills.size() <= n ? skills : skills.subList(0, n);
    }
}
