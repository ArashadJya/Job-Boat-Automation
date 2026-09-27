package com.jobhunter;

import com.jobhunter.model.Job;

import java.util.ArrayList;
import java.util.List;

/**
 * Scores a {@link Job} against a candidate's skills and target role.
 */
public final class JobScorer {

    private static final List<String> FRESHER_WORDS = List.of(
            "junior", "entry", "entry-level", "graduate", "fresher", "intern",
            "trainee", "associate", "early career", "0-1 year", "no experience", "new grad"
    );

    private static final List<String> SENIOR_WORDS = List.of(
            "senior", "sr.", "sr ", "staff", "principal", "lead", "architect",
            "manager", "head of", "director", "10+ years", "8+ years",
            "7+ years", "6+ years", "5+ years"
    );

    private static final List<String> LOCATION_WORDS = List.of(
            "india", "remote", "worldwide", "anywhere", "asia", "apac", "global",
            "bangalore", "bengaluru", "hyderabad", "pune", "chennai", "mumbai",
            "delhi", "gurgaon", "gurugram", "noida", "kolkata", "ahmedabad"
    );

    private static final List<String> DEVELOPER_WORDS = List.of(
            "developer", "engineer", "programmer", "software", "sde", "frontend",
            "front end", "backend", "back end", "full stack", "fullstack", "data",
            "devops", "qa", "test", "android", "ios", "web", "python", "java",
            "ml", "ai", "cloud", "support engineer", "analyst", "sql"
    );

    private static final List<String> WALKIN_WORDS = List.of(
            "walk-in", "walkin", "walk in drive", "walkin drive"
    );

    private JobScorer() {
        // static utility
    }

    /** Result of scoring one job: overall percentage + which resume skills matched. */
    public record ScoreResult(int score, List<String> matchedSkills) {}

    public static ScoreResult score(Job job, List<String> skills, String targetRole) {
        String haystack = ((job.title() == null ? "" : job.title()) + " "
                + (job.description() == null ? "" : job.description())).toLowerCase();
        String titleLower = job.title() == null ? "" : job.title().toLowerCase();
        String locationLower = job.location() == null ? "" : job.location().toLowerCase();

        List<String> matched = new ArrayList<>();
        for (String skill : skills) {
            if (skill == null || skill.isBlank()) continue;
            if (haystack.contains(skill.toLowerCase())) {
                matched.add(skill);
            }
        }

        int totalSkills = skills.size();
        int denom = Math.max(4, Math.min(totalSkills, 8));
        double skillScore = Math.min(matched.size() / (double) denom, 1.0) * 55.0;

        double fresherBonus = containsAny(haystack, FRESHER_WORDS) ? 20.0 : 0.0;
        double seniorPenalty = containsAny(titleLower, SENIOR_WORDS) ? -30.0 : 0.0;
        double locationBonus = (locationLower.isBlank() || containsAny(locationLower, LOCATION_WORDS)) ? 15.0 : 0.0;
        double roleBonus = (!targetRole.isBlank() && titleLower.contains(targetRole.toLowerCase())) ? 15.0 : 0.0;

        double sum = skillScore + fresherBonus + seniorPenalty + locationBonus + roleBonus;
        int finalScore = (int) Math.round(clamp(sum, 0, 100));

        return new ScoreResult(finalScore, matched);
    }

    public static boolean isDeveloperJob(Job job) {
        String titleLower = job.title() == null ? "" : job.title().toLowerCase();
        return containsAny(titleLower, DEVELOPER_WORDS);
    }

    public static boolean isWalkinJob(Job job) {
        String haystack = ((job.title() == null ? "" : job.title()) + " "
                + (job.description() == null ? "" : job.description())).toLowerCase();
        return containsAny(haystack, WALKIN_WORDS);
    }

    private static boolean containsAny(String haystack, List<String> needles) {
        for (String n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
