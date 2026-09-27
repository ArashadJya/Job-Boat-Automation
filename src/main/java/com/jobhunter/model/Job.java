package com.jobhunter.model;

/**
 * A normalized job listing, regardless of which free API it came from.
 * <p>
 * Fields not provided by a given source are left as empty strings ("") —
 * never null — so downstream code (scoring, HTML rendering) never needs
 * null checks.
 *
 * @param title       job title, e.g. "Junior Java Developer"
 * @param company     hiring company name
 * @param location    free-text location, e.g. "Bangalore, India" or "Remote"
 * @param url         canonical apply/listing URL (used for de-duplication)
 * @param source      short source name, e.g. "Remotive", "Greenhouse:postman"
 * @param description plain-text job description (HTML stripped)
 * @param postedDate  ISO-ish date string as reported by the source, or ""
 */
public record Job(String title, String company, String location, String url,
                   String source, String description, String postedDate) {

    /** Stable de-duplication key: URL if present, else title|company. */
    public String dedupeKey() {
        if (url != null && !url.isBlank()) {
            return url.trim();
        }
        return (title == null ? "" : title.trim().toLowerCase())
                + "|" + (company == null ? "" : company.trim().toLowerCase());
    }

    /** True when this job's URL points at a Greenhouse or Lever board (real auto-apply tier). */
    public boolean isCompanyBoardUrl() {
        if (url == null) return false;
        String u = url.toLowerCase();
        return u.contains("boards.greenhouse.io") || u.contains("greenhouse.io")
                || u.contains("jobs.lever.co") || u.contains("lever.co");
    }
}
