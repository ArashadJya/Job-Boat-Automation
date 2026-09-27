package com.jobhunter;

import com.jobhunter.model.Profile;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Two automation tiers:
 * <ol>
 *   <li><b>Company boards (Greenhouse / Lever)</b> — these are normal REST
 *       endpoints meant for programmatic submission, so a real HTTP POST is
 *       fine. Dry-run by default; pass {@code --live} to actually send.</li>
 *   <li><b>LinkedIn / Naukri (assisted fill)</b> — these platforms actively
 *       detect and permanently ban automated submissions. We open a real,
 *       visible Chrome window via Selenium, fill in the visible fields, and
 *       stop before Submit so a human reviews and clicks it themselves.</li>
 * </ol>
 */
public final class AutoApply {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(25))
            .build();

    private AutoApply() {
        // static utility
    }

    // ------------------------------------------------------------------
    // Tier 1: Greenhouse / Lever — real programmatic submission
    // ------------------------------------------------------------------

    /**
     * Applies (or dry-runs) to a Greenhouse/Lever job posting URL.
     *
     * @param url          the job's apply URL (boards.greenhouse.io/... or jobs.lever.co/...)
     * @param profile      candidate profile (name/email/phone)
     * @param resumePath   path to the resume file to attach (may be null in dry-run)
     * @param coverLetter  generated cover letter text
     * @param dryRun       true = print what WOULD be sent, do not send; false = actually POST
     */
    public static Map<String, Object> autoApplyCompanyBoard(String url, Profile profile, Path resumePath,
                                                              String coverLetter, boolean dryRun) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("url", url);
        result.put("dryRun", dryRun);

        Map<String, String> formFields = new LinkedHashMap<>();
        formFields.put("first_name", firstName(profile.name()));
        formFields.put("last_name", lastName(profile.name()));
        formFields.put("email", profile.email());
        formFields.put("phone", profile.phone());
        formFields.put("cover_letter", coverLetter);
        String resumeFileName = resumePath == null ? "(none)" : resumePath.getFileName().toString();

        System.out.println("  ------------------------------------------------------------");
        System.out.println("  Company-board " + (dryRun ? "DRY RUN" : "LIVE APPLY") + " -> " + url);
        System.out.println("  Headers: User-Agent: ResumeJobHunter/4.0, Accept: application/json");
        System.out.println("  Form fields:");
        formFields.forEach((k, v) -> System.out.println("    " + k + " = " + v));
        System.out.println("  Resume file: " + resumeFileName);
        System.out.println("  Cover letter preview: " + preview(coverLetter, 120));
        System.out.println("  ------------------------------------------------------------");

        if (dryRun) {
            System.out.println("  (Dry run — nothing was sent. Re-run with --live to actually submit.)");
            result.put("status", "dry_run_only");
            return result;
        }

        try {
            String boundary = "----ResumeJobHunterBoundary" + System.currentTimeMillis();
            var bodyPublisher = buildMultipart(boundary, formFields, resumePath);

            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "ResumeJobHunter/4.0 (personal job search tool)")
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .timeout(Duration.ofSeconds(30))
                    .POST(bodyPublisher)
                    .build();

            HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            System.out.println("  -> HTTP " + resp.statusCode());
            result.put("status", "sent");
            result.put("httpStatus", resp.statusCode());
        } catch (Exception e) {
            System.err.println("  ! Live apply failed: " + e.getMessage());
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    private static HttpRequest.BodyPublisher buildMultipart(String boundary, Map<String, String> fields,
                                                              Path resumePath) throws Exception {
        List<byte[]> parts = new java.util.ArrayList<>();
        for (var entry : fields.entrySet()) {
            String part = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"" + entry.getKey() + "\"\r\n\r\n"
                    + entry.getValue() + "\r\n";
            parts.add(part.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        if (resumePath != null && Files.exists(resumePath)) {
            String header = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"resume\"; filename=\""
                    + resumePath.getFileName() + "\"\r\n"
                    + "Content-Type: application/octet-stream\r\n\r\n";
            parts.add(header.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            parts.add(Files.readAllBytes(resumePath));
            parts.add("\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        parts.add(("--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));

        int totalLen = parts.stream().mapToInt(b -> b.length).sum();
        byte[] all = new byte[totalLen];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, all, pos, p.length);
            pos += p.length;
        }
        return HttpRequest.BodyPublishers.ofByteArray(all);
    }

    // ------------------------------------------------------------------
    // Tier 2: LinkedIn / Naukri — assisted fill, human clicks Submit
    // ------------------------------------------------------------------

    /**
     * Opens a real Chrome window (via Selenium Manager, no manual driver setup),
     * navigates to the given URL, and fills visible name/email/phone/cover-letter
     * fields by id/name/placeholder/aria-label heuristics. Stops before Submit —
     * the human reviews and submits manually, since these platforms ban accounts
     * that auto-submit.
     */
    public static void assistedFill(String url, Profile profile, String coverLetter, String pitch) {
        System.out.println("  Launching Chrome for assisted fill: " + url);
        ChromeOptions options = new ChromeOptions();
        WebDriver driver = null;
        try {
            driver = new ChromeDriver(options);
            driver.get(url);

            fillFirstMatch(driver, List.of(
                    "input#name", "input[name='name']", "input[placeholder*='name' i]",
                    "input[aria-label*='name' i]"), profile.name());
            fillFirstMatch(driver, List.of(
                    "input#email", "input[name='email']", "input[type='email']",
                    "input[placeholder*='email' i]", "input[aria-label*='email' i]"), profile.email());
            fillFirstMatch(driver, List.of(
                    "input#phone", "input[name='phone']", "input[type='tel']",
                    "input[placeholder*='phone' i]", "input[aria-label*='phone' i]"), profile.phone());
            fillFirstMatch(driver, List.of(
                    "textarea#cover_letter", "textarea[name*='cover' i]",
                    "textarea[placeholder*='cover' i]", "textarea[aria-label*='cover' i]"), coverLetter);
            fillFirstMatch(driver, List.of(
                    "textarea#pitch", "textarea[name*='pitch' i]", "textarea[name*='summary' i]",
                    "textarea[placeholder*='about you' i]", "textarea[aria-label*='summary' i]"), pitch);

            System.out.println("  Fields filled where detected. Review the form and click Submit");
            System.out.println("  yourself \u2014 LinkedIn/Naukri ban accounts that auto-submit.");
            System.out.println("  (Leaving the browser window open for you.)");
        } catch (Exception e) {
            System.err.println("  ! Assisted fill failed: " + e.getMessage());
            if (driver != null) {
                driver.quit();
            }
        }
        // Intentionally do NOT call driver.quit() on success — leave the browser
        // open so the human can inspect and submit the form themselves.
    }

    private static void fillFirstMatch(WebDriver driver, List<String> selectors, String value) {
        if (value == null || value.isBlank()) return;
        for (String selector : selectors) {
            try {
                List<WebElement> els = driver.findElements(By.cssSelector(selector));
                if (!els.isEmpty() && els.get(0).isDisplayed()) {
                    els.get(0).clear();
                    els.get(0).sendKeys(value);
                    return;
                }
            } catch (Exception ignored) {
                // try next selector
            }
        }
    }

    // ------------------------------------------------------------------
    private static String firstName(String fullName) {
        if (fullName == null || fullName.isBlank()) return "";
        return fullName.strip().split("\\s+")[0];
    }

    private static String lastName(String fullName) {
        if (fullName == null || fullName.isBlank()) return "";
        String[] parts = fullName.strip().split("\\s+");
        return parts.length > 1 ? parts[parts.length - 1] : "";
    }

    private static String preview(String s, int maxLen) {
        if (s == null) return "";
        String oneLine = s.replace("\n", " ").strip();
        return oneLine.length() <= maxLen ? oneLine : oneLine.substring(0, maxLen) + "...";
    }
}
