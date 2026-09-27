package com.jobhunter;

import com.jobhunter.model.Job;
import com.jobhunter.model.Profile;
import com.jobhunter.model.RunContext;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Manages date-stamped, ever-incrementing run folders under output/, and the
 * applied_jobs.csv history file used to tell "new" jobs from ones already
 * shown on an earlier run's dashboard.
 */
public final class Tracker {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final Path CSV_PATH = Path.of("applied_jobs.csv");
    private static final Path OUTPUT_ROOT = Path.of("output");

    private Tracker() {
        // static utility
    }

    /**
     * Creates (if needed) output/&lt;today&gt;/ and returns a fresh, never-reused
     * run folder output/&lt;today&gt;/&lt;n&gt;/ where n = (highest existing subfolder) + 1.
     */
    public static RunContext nextRunFolder() throws IOException {
        LocalDate today = LocalDate.now();
        Path dateFolder = OUTPUT_ROOT.resolve(today.format(DATE_FMT));
        Files.createDirectories(dateFolder);

        int max = 0;
        try (Stream<Path> children = Files.list(dateFolder)) {
            for (Path child : (Iterable<Path>) children::iterator) {
                if (!Files.isDirectory(child)) continue;
                try {
                    int n = Integer.parseInt(child.getFileName().toString());
                    max = Math.max(max, n);
                } catch (NumberFormatException ignored) {
                    // not a run-number folder; skip
                }
            }
        }

        int runNo = max + 1;
        Path runFolder = dateFolder.resolve(String.valueOf(runNo));
        Files.createDirectories(runFolder);
        return new RunContext(today, runNo, runFolder);
    }

    /** Loads every URL/key previously written to applied_jobs.csv. */
    public static Set<String> loadSeenUrls() {
        Set<String> seen = new HashSet<>();
        if (!Files.exists(CSV_PATH)) return seen;
        try {
            List<String> lines = Files.readAllLines(CSV_PATH, StandardCharsets.UTF_8);
            boolean first = true;
            for (String line : lines) {
                if (first) { // skip header
                    first = false;
                    continue;
                }
                if (line.isBlank()) continue;
                String[] cols = splitCsvLine(line);
                if (cols.length >= 6) {
                    String url = cols[5];
                    if (!url.isBlank()) seen.add(url);
                }
            }
        } catch (IOException e) {
            System.err.println("  ! Failed to read applied_jobs.csv: " + e.getMessage());
        }
        return seen;
    }

    /** Appends only jobs that were NOT already in applied_jobs.csv (writes header if the file is new). */
    public static void saveNewJobs(List<Job> jobs, Profile profile) {
        Set<String> alreadySeen = loadSeenUrls();
        boolean isNewFile = !Files.exists(CSV_PATH);
        try (BufferedWriter writer = Files.newBufferedWriter(CSV_PATH, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            if (isNewFile) {
                writer.write("date_found,resume,title,company,score,url,status");
                writer.newLine();
            }
            String today = LocalDate.now().format(DATE_FMT);
            for (Job job : jobs) {
                String key = job.dedupeKey();
                if (alreadySeen.contains(key)) continue;
                var result = JobScorer.score(job, profile.skills(), profile.targetRole());
                writer.write(String.join(",",
                        csv(today), csv(profile.file()), csv(job.title()), csv(job.company()),
                        String.valueOf(result.score()), csv(job.url()), csv("shown_on_dashboard")));
                writer.newLine();
            }
        } catch (IOException e) {
            System.err.println("  ! Failed to write applied_jobs.csv: " + e.getMessage());
        }
    }

    private static String csv(String s) {
        if (s == null) s = "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static String[] splitCsvLine(String line) {
        // Minimal CSV split good enough for our own writer's quoting style.
        List<String> out = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == ',') {
                    out.add(cur.toString());
                    cur.setLength(0);
                } else {
                    cur.append(c);
                }
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }
}
