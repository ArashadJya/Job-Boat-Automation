package com.jobhunter;

import com.jobhunter.model.Job;
import com.jobhunter.model.Profile;
import com.jobhunter.model.RunContext;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Entry point: parses CLI flags, then either runs the auto-apply / assisted-fill
 * branch for a single job, or runs the full 5-step resume -> dashboard flow.
 */
public final class Main {

    private static final int MAX_JOBS = 120;
    private static final int MIN_SCORE = 30;

    private Main() {
        // entry point only
    }

    public static void main(String[] args) {
        printBanner();

        CliArgs cli = CliArgs.parse(args);

        if (cli.autoApplyUrl() != null) {
            runAutoApplyBranch(cli);
            return;
        }
        if (cli.assistUrl() != null) {
            runAssistBranch(cli);
            return;
        }

        runFullFlow();
    }

    private static void printBanner() {
        System.out.println("==========================================================");
        System.out.println(" JOB HUNTER v4 (pure Java, HTML dashboard output)");
        System.out.println("==========================================================");
    }

    // ------------------------------------------------------------------
    // CLI-only branches: --auto-apply / --assist
    // ------------------------------------------------------------------
    private static void runAutoApplyBranch(CliArgs cli) {
        Profile profile = firstAvailableProfileOrEmpty();
        Path resumePath = firstResumeFileOrNull();
        String coverLetter = "Dear Hiring Team,\n\nPlease find my application attached.\n\nSincerely,\n"
                + profile.name();
        AutoApply.autoApplyCompanyBoard(cli.autoApplyUrl(), profile, resumePath, coverLetter, cli.live());
    }

    private static void runAssistBranch(CliArgs cli) {
        Profile profile = firstAvailableProfileOrEmpty();
        String coverLetter = "Dear Hiring Team,\n\nPlease consider my application.\n\nSincerely,\n" + profile.name();
        String pitch = "Candidate skilled in " + String.join(", ", profile.topSkills(5)) + ".";
        AutoApply.assistedFill(cli.assistUrl(), profile, coverLetter, pitch);
    }

    private static Profile firstAvailableProfileOrEmpty() {
        Path resume = firstResumeFileOrNull();
        if (resume == null) {
            return new Profile("(none)", "Candidate", "", "", List.of(), "");
        }
        return ResumeParser.parse(resume);
    }

    private static Path firstResumeFileOrNull() {
        Path dir = Path.of("input_resumes");
        if (!Files.isDirectory(dir)) return null;
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Main::isResumeFile).findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Full flow: resumes -> live jobs -> matching -> quick links -> dashboard
    // ------------------------------------------------------------------
    private static void runFullFlow() {
        long flowStart = System.currentTimeMillis();

        // STEP 1: RESUME READING
        // ------------------------------------------------------------------
        long t1 = System.currentTimeMillis();
        System.out.println("[1/5] Reading resumes from 'input_resumes' ...");
        List<Path> resumeFiles = listResumeFiles();
        if (resumeFiles.isEmpty()) {
            System.out.println();
            System.out.println("  No resume found. Put a PDF or DOCX inside the 'input_resumes' folder and run again.");
            return;
        }
        List<Profile> profiles = new ArrayList<>();
        for (Path file : resumeFiles) {
            profiles.add(ResumeParser.parse(file));
        }
        System.out.printf("      done (%d sec)%n", secondsSince(t1));

        // Process the first resume found (multi-resume batches reuse steps 2-5 per profile).
        for (Profile profile : profiles) {
            processOneProfile(profile);
        }

        System.out.printf("%nTotal elapsed: %d sec%n", secondsSince(flowStart));
    }

    private static void processOneProfile(Profile profile) {
        long profileStart = System.currentTimeMillis();

        // STEP 2: FETCH LIVE JOBS
        // ------------------------------------------------------------------
        long t2 = System.currentTimeMillis();
        System.out.println("[2/5] Fetching live jobs from 8 free sources ...");
        List<Job> allJobs = JobFetcher.fetchAll();
        System.out.printf("      Total unique jobs collected: %d (%d sec)%n", allJobs.size(), secondsSince(t2));

        // STEP 3: MATCH & SCORE
        // ------------------------------------------------------------------
        long t3 = System.currentTimeMillis();
        System.out.println("[3/5] Matching jobs for " + profile.name() + " ...");
        Set<String> seenUrls = Tracker.loadSeenUrls();

        List<Job> matched = allJobs.stream()
                .filter(JobScorer::isDeveloperJob)
                .filter(job -> JobScorer.score(job, profile.skills(), profile.targetRole()).score() >= MIN_SCORE)
                .sorted(Comparator
                        .comparing((Job j) -> seenUrls.contains(j.dedupeKey())) // false (new) sorts first
                        .thenComparing(j -> -JobScorer.score(j, profile.skills(), profile.targetRole()).score()))
                .limit(MAX_JOBS)
                .toList();

        long newCount = matched.stream().filter(j -> !seenUrls.contains(j.dedupeKey())).count();
        long earlierCount = matched.size() - newCount;
        System.out.printf("      %d matched (%d sec)%n", matched.size(), secondsSince(t3));
        System.out.printf("      (%d new, %d from earlier runs)%n", newCount, earlierCount);

        // STEP 4: QUICK APPLY LINKS
        // ------------------------------------------------------------------
        long t4 = System.currentTimeMillis();
        System.out.println("[4/5] Building Naukri + LinkedIn + Shine + Indeed quick-apply links ...");
        List<QuickLinks.Group> groups = QuickLinks.build(profile);
        int totalLinks = groups.stream().mapToInt(g -> g.links().size()).sum();
        System.out.printf("      %d links (%d sec)%n", totalLinks, secondsSince(t4));

        // STEP 5: BUILD DASHBOARD
        // ------------------------------------------------------------------
        long t5 = System.currentTimeMillis();
        System.out.println("[5/5] Generating HTML dashboard ...");
        try {
            RunContext ctx = Tracker.nextRunFolder();
            Path dashboard = DashboardBuilder.build(profile, matched, groups, ctx.runFolder(), ctx.runNo(), seenUrls);
            Tracker.saveNewJobs(matched, profile);

            System.out.println("      done");
            System.out.printf("  \u2713 COMPLETED in %d seconds  (today's run number: %d)%n",
                    secondsSince(profileStart), ctx.runNo());
            System.out.println("  \u2713 Dashboard: " + dashboard.toAbsolutePath());

            openInBrowser(dashboard);
        } catch (IOException e) {
            System.err.println("  ! Failed to build dashboard: " + e.getMessage());
        }
    }

    private static void openInBrowser(Path dashboard) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(dashboard.toUri());
            }
        } catch (Exception e) {
            // Headless or no default browser configured — skip silently, path was already printed.
        }
    }

    private static List<Path> listResumeFiles() {
        Path dir = Path.of("input_resumes");
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Main::isResumeFile).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static boolean isResumeFile(Path p) {
        if (!Files.isRegularFile(p)) return false;
        String name = p.getFileName().toString().toLowerCase();
        return name.endsWith(".pdf") || name.endsWith(".docx");
    }

    private static long secondsSince(long startMillis) {
        return Math.round((System.currentTimeMillis() - startMillis) / 1000.0);
    }

    // ------------------------------------------------------------------
    // Minimal CLI parser
    // ------------------------------------------------------------------
    private record CliArgs(String autoApplyUrl, String assistUrl, boolean live, String company, String title) {
        static CliArgs parse(String[] args) {
            String autoApply = null, assist = null, company = null, title = null;
            boolean live = false;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--auto-apply" -> autoApply = valueAfter(args, i);
                    case "--assist" -> assist = valueAfter(args, i);
                    case "--live" -> live = true;
                    case "--company" -> company = valueAfter(args, i);
                    case "--title" -> title = valueAfter(args, i);
                    default -> { /* ignore unknown flags */ }
                }
            }
            return new CliArgs(autoApply, assist, live, company, title);
        }

        private static String valueAfter(String[] args, int i) {
            return (i + 1 < args.length) ? args[i + 1] : null;
        }
    }
}
