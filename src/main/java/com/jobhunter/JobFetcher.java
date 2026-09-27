package com.jobhunter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobhunter.model.Job;
import org.jsoup.Jsoup;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fetches live job listings from every free, no-key-required API source.
 * <p>
 * Every source call is wrapped in its own try/catch so a single failing
 * or rate-limited source never crashes the whole run — it just contributes
 * zero jobs and the app moves on.
 */
public final class JobFetcher {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(25))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Default Greenhouse company-board slugs (no signup needed to read these). */
    private static final List<String> GREENHOUSE_SLUGS = List.of("postman");

    /** Default Lever company-board slugs. */
    private static final List<String> LEVER_SLUGS = List.of("cred", "zeta");

    private JobFetcher() {
        // static utility
    }

    /**
     * Fetches from all configured sources, printing a per-source progress line,
     * and returns a deduplicated list (by URL, else title|company).
     */
    public static List<Job> fetchAll() {
        record Source(String name, java.util.function.Supplier<List<Job>> fetcher) {}

        List<Source> sources = new ArrayList<>();
        sources.add(new Source("Remotive", JobFetcher::fetchRemotive));
        sources.add(new Source("RemoteOK", JobFetcher::fetchRemoteOk));
        sources.add(new Source("Arbeitnow", JobFetcher::fetchArbeitnow));
        sources.add(new Source("Jobicy", JobFetcher::fetchJobicy));
        sources.add(new Source("Himalayas", JobFetcher::fetchHimalayas));
        sources.add(new Source("The Muse", JobFetcher::fetchTheMuse));
        sources.add(new Source("Greenhouse", JobFetcher::fetchGreenhouse));
        sources.add(new Source("Lever", JobFetcher::fetchLever));

        Map<String, Job> dedup = new LinkedHashMap<>();
        int total = sources.size();
        int i = 0;
        for (Source src : sources) {
            i++;
            long start = System.currentTimeMillis();
            List<Job> jobs;
            try {
                jobs = src.fetcher().get();
            } catch (Exception e) {
                jobs = List.of();
                System.err.println("      ! " + src.name() + " failed: " + e.getMessage());
            }
            for (Job j : jobs) {
                dedup.putIfAbsent(j.dedupeKey(), j);
            }
            double secs = (System.currentTimeMillis() - start) / 1000.0;
            System.out.printf("      [%s] %d/%d  %-12s %4d jobs (%.0f sec)%n",
                    progressBar(i, total), i, total, src.name() + "...", jobs.size(), secs);
        }
        return new ArrayList<>(dedup.values());
    }

    private static String progressBar(int i, int total) {
        int filled = (int) Math.round((i / (double) total) * 10);
        return "#".repeat(filled) + "-".repeat(10 - filled);
    }

    private static String get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "ResumeJobHunter/4.0 (personal job search tool)")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(25))
                .GET()
                .build();
        HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("HTTP " + resp.statusCode() + " from " + url);
        }
        return resp.body();
    }

    private static String stripHtml(String html) {
        if (html == null || html.isBlank()) return "";
        try {
            return Jsoup.parse(html).text();
        } catch (Exception e) {
            return html.replaceAll("<[^>]+>", " ").strip();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText("");
    }

    // ------------------------------------------------------------------
    // Remotive: https://remotive.com/api/remote-jobs?category=software-dev
    // ------------------------------------------------------------------
    private static List<Job> fetchRemotive() {
        List<Job> out = new ArrayList<>();
        try {
            String body = get("https://remotive.com/api/remote-jobs?category=software-dev");
            JsonNode root = MAPPER.readTree(body);
            for (JsonNode j : root.path("jobs")) {
                out.add(new Job(
                        text(j, "title"),
                        text(j, "company_name"),
                        text(j, "candidate_required_location"),
                        text(j, "url"),
                        "Remotive",
                        stripHtml(text(j, "description")),
                        text(j, "publication_date")
                ));
            }
        } catch (Exception e) {
            System.err.println("      ! Remotive failed: " + e.getMessage());
        }
        return out;
    }

    // ------------------------------------------------------------------
    // RemoteOK: https://remoteok.com/api
    // ------------------------------------------------------------------
    private static List<Job> fetchRemoteOk() {
        List<Job> out = new ArrayList<>();
        try {
            String body = get("https://remoteok.com/api");
            JsonNode root = MAPPER.readTree(body);
            for (JsonNode j : root) {
                if (!j.has("position") && !j.has("title")) continue; // first element is legal notice
                String title = j.has("position") ? text(j, "position") : text(j, "title");
                String slug = text(j, "slug");
                String url = text(j, "url");
                if (url.isBlank() && !slug.isBlank()) url = "https://remoteok.com/remote-jobs/" + slug;
                out.add(new Job(
                        title,
                        text(j, "company"),
                        text(j, "location"),
                        url,
                        "RemoteOK",
                        stripHtml(text(j, "description")),
                        text(j, "date")
                ));
            }
        } catch (Exception e) {
            System.err.println("      ! RemoteOK failed: " + e.getMessage());
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Arbeitnow: paginated job-board API
    // ------------------------------------------------------------------
    private static List<Job> fetchArbeitnow() {
        List<Job> out = new ArrayList<>();
        for (int page = 1; page <= 3; page++) {
            try {
                String body = get("https://www.arbeitnow.com/api/job-board-api?page=" + page);
                JsonNode root = MAPPER.readTree(body);
                JsonNode data = root.path("data");
                if (!data.isArray() || data.isEmpty()) break;
                for (JsonNode j : data) {
                    out.add(new Job(
                            text(j, "title"),
                            text(j, "company_name"),
                            text(j, "location"),
                            text(j, "url"),
                            "Arbeitnow",
                            stripHtml(text(j, "description")),
                            text(j, "created_at")
                    ));
                }
            } catch (Exception e) {
                System.err.println("      ! Arbeitnow page " + page + " failed: " + e.getMessage());
                break;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Jobicy: https://jobicy.com/api/v2/remote-jobs?count=100
    // ------------------------------------------------------------------
    private static List<Job> fetchJobicy() {
        List<Job> out = new ArrayList<>();
        try {
            String body = get("https://jobicy.com/api/v2/remote-jobs?count=100");
            JsonNode root = MAPPER.readTree(body);
            for (JsonNode j : root.path("jobs")) {
                out.add(new Job(
                        text(j, "jobTitle"),
                        text(j, "companyName"),
                        text(j, "jobGeo"),
                        text(j, "url"),
                        "Jobicy",
                        stripHtml(text(j, "jobExcerpt")),
                        text(j, "pubDate")
                ));
            }
        } catch (Exception e) {
            System.err.println("      ! Jobicy failed: " + e.getMessage());
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Himalayas: https://himalayas.app/jobs/api?limit=100
    // ------------------------------------------------------------------
    private static List<Job> fetchHimalayas() {
        List<Job> out = new ArrayList<>();
        try {
            String body = get("https://himalayas.app/jobs/api?limit=100");
            JsonNode root = MAPPER.readTree(body);
            JsonNode jobs = root.has("jobs") ? root.path("jobs") : root.path("data");
            for (JsonNode j : jobs) {
                String url = text(j, "applicationLink");
                if (url.isBlank()) url = text(j, "url");
                if (url.isBlank()) {
                    String guid = text(j, "guid");
                    if (!guid.isBlank()) url = "https://himalayas.app/jobs/" + guid;
                }
                String location = "";
                JsonNode locs = j.get("locationRestrictions");
                if (locs != null && locs.isArray() && locs.size() > 0) {
                    List<String> l = new ArrayList<>();
                    locs.forEach(n -> l.add(n.asText("")));
                    location = String.join(", ", l);
                }
                out.add(new Job(
                        text(j, "title"),
                        text(j, "companyName"),
                        location,
                        url,
                        "Himalayas",
                        stripHtml(text(j, "description")),
                        text(j, "pubDate")
                ));
            }
        } catch (Exception e) {
            System.err.println("      ! Himalayas failed: " + e.getMessage());
        }
        return out;
    }

    // ------------------------------------------------------------------
    // The Muse: category + level filtered, paginated
    // ------------------------------------------------------------------
    private static List<Job> fetchTheMuse() {
        List<Job> out = new ArrayList<>();
        for (int page = 0; page <= 2; page++) {
            try {
                String url = "https://www.themuse.com/api/public/jobs?category=Software+Engineering"
                        + "&level=Entry+Level&page=" + page;
                String body = get(url);
                JsonNode root = MAPPER.readTree(body);
                JsonNode results = root.path("results");
                if (!results.isArray() || results.isEmpty()) break;
                for (JsonNode j : results) {
                    String company = j.path("company").path("name").asText("");
                    String link = j.path("refs").path("landing_page").asText("");
                    StringBuilder locs = new StringBuilder();
                    for (JsonNode loc : j.path("locations")) {
                        if (locs.length() > 0) locs.append(", ");
                        locs.append(loc.path("name").asText(""));
                    }
                    out.add(new Job(
                            text(j, "name"),
                            company,
                            locs.toString(),
                            link,
                            "The Muse",
                            stripHtml(text(j, "contents")),
                            text(j, "publication_date")
                    ));
                }
            } catch (Exception e) {
                System.err.println("      ! The Muse page " + page + " failed: " + e.getMessage());
                break;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Greenhouse: real auto-apply tier
    // ------------------------------------------------------------------
    private static List<Job> fetchGreenhouse() {
        List<Job> out = new ArrayList<>();
        for (String slug : GREENHOUSE_SLUGS) {
            try {
                String body = get("https://boards-api.greenhouse.io/v1/boards/" + slug + "/jobs");
                JsonNode root = MAPPER.readTree(body);
                for (JsonNode j : root.path("jobs")) {
                    String location = j.path("location").path("name").asText("");
                    out.add(new Job(
                            text(j, "title"),
                            capitalize(slug),
                            location,
                            text(j, "absolute_url"),
                            "Greenhouse:" + slug,
                            "",
                            text(j, "updated_at")
                    ));
                }
            } catch (Exception e) {
                System.err.println("      ! Greenhouse:" + slug + " failed: " + e.getMessage());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Lever: real auto-apply tier
    // ------------------------------------------------------------------
    private static List<Job> fetchLever() {
        List<Job> out = new ArrayList<>();
        for (String slug : LEVER_SLUGS) {
            try {
                String body = get("https://api.lever.co/v0/postings/" + slug + "?mode=json");
                JsonNode root = MAPPER.readTree(body);
                for (JsonNode j : root) {
                    String location = j.path("categories").path("location").asText("");
                    out.add(new Job(
                            text(j, "text"),
                            capitalize(slug),
                            location,
                            text(j, "hostedUrl"),
                            "Lever:" + slug,
                            stripHtml(text(j, "descriptionPlain").isBlank()
                                    ? text(j, "description") : text(j, "descriptionPlain")),
                            String.valueOf(j.path("createdAt").asLong(0))
                    ));
                }
            } catch (Exception e) {
                System.err.println("      ! Lever:" + slug + " failed: " + e.getMessage());
            }
        }
        return out;
    }

    private static String capitalize(String s) {
        if (s == null || s.isBlank()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
