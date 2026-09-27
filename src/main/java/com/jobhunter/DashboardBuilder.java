package com.jobhunter;

import com.jobhunter.model.Job;
import com.jobhunter.model.Profile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

import static com.jobhunter.HtmlEscape.escape;

/**
 * Builds the single self-contained dashboard.html — inline CSS, inline JS,
 * no CDN, no external images, no template engine. Large static chunks use
 * text blocks; per-job / per-group content is assembled with StringBuilder.
 */
public final class DashboardBuilder {

    private DashboardBuilder() {
        // static utility
    }

    /**
     * Renders the dashboard and writes it to runFolder/dashboard.html.
     *
     * @param profile   the candidate's extracted resume profile
     * @param jobs      matched, scored, sorted jobs to render (already filtered/capped by caller)
     * @param groups    Quick Apply link groups
     * @param runFolder output/&lt;date&gt;/&lt;n&gt;/ folder to write into
     * @param runNo     today's run number, for the header/footer text
     * @param seenUrls  URLs already shown on an earlier run (for NEW vs "seen" badges)
     * @return path to the written dashboard.html
     */
    public static Path build(Profile profile, List<Job> jobs, List<QuickLinks.Group> groups,
                              Path runFolder, int runNo, Set<String> seenUrls) throws IOException {

        String today = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        long newCount = jobs.stream().filter(j -> !seenUrls.contains(j.dedupeKey())).count();
        long earlierCount = jobs.size() - newCount;

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n");
        html.append("<html lang=\"en\">\n<head>\n");
        html.append("<meta charset=\"utf-8\">\n");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
        html.append("<title>Job Hunter \u00b7 ").append(escape(profile.name()))
                .append(" \u00b7 ").append(today).append(" \u00b7 run ").append(runNo).append("</title>\n");
        html.append("<style>\n").append(CSS).append("\n</style>\n");
        html.append("</head>\n<body>\n");

        // ---------------- Header ----------------
        html.append("<header>\n<div class=\"wrap\">\n");
        html.append("<h1>Job Hunter dashboard \u00b7 ").append(today)
                .append(" \u00b7 run ").append(runNo).append("</h1>\n");
        html.append("<div class=\"sub\">").append(escape(profile.name()))
                .append(" \u00b7 ").append(jobs.size()).append(" matched jobs (")
                .append(newCount).append(" new, ").append(earlierCount).append(" from earlier runs)")
                .append(" \u00b7 generated ").append(timestamp)
                .append(" \u00b7 skills: ").append(escape(String.join(", ", profile.topSkills(10))))
                .append("</div>\n");
        html.append("</div>\n</header>\n");

        html.append("<div class=\"wrap\">\n");

        // ---------------- Notice box ----------------
        html.append("<div class=\"notice\">\n");
        html.append("<b>How to use:</b> Start with the Quick Apply buttons below \u2014 each opens a ")
                .append("pre-filtered fresher search where you apply in one click. Then work through the ")
                .append("matched jobs list. There are two automation tiers: <b>Greenhouse/Lever</b> postings ")
                .append("support real auto-apply (dry-run by default \u2014 add <code>--live</code> to actually ")
                .append("send); <b>LinkedIn/Naukri</b> postings only get assisted fill (opens a real Chrome ")
                .append("window, fills your details, and stops before Submit) because those platforms ")
                .append("permanently ban accounts that auto-submit. Walk-in drives are LinkedIn feed posts, ")
                .append("not job listings, so the \u201cWalk-in drives\u201d group searches LinkedIn's post/content ")
                .append("search instead of the Jobs tab.\n");
        html.append("</div>\n");

        // ---------------- Quick Apply ----------------
        html.append("<div class=\"card\">\n");
        for (QuickLinks.Group group : groups) {
            html.append("<div class=\"grouplabel\">").append(escape(group.name())).append("</div>\n");
            html.append("<div class=\"linkrow\">\n");
            for (QuickLinks.Link link : group.links()) {
                html.append("<a class=\"btn linkbtn\" href=\"").append(escape(link.url()))
                        .append("\" target=\"_blank\" rel=\"noopener\">")
                        .append(escape(link.label())).append("</a>\n");
            }
            html.append("</div>\n");
        }
        html.append("</div>\n");

        // ---------------- Matched jobs ----------------
        int i = 0;
        for (Job job : jobs) {
            i++;
            html.append(renderJobCard(job, profile, i, seenUrls));
        }

        html.append("</div>\n"); // .wrap

        // ---------------- Footer ----------------
        html.append("<footer>Job Hunter v4 \u00b7 free &amp; open sources only \u00b7 ")
                .append("every run saved in output/&lt;date&gt;/&lt;run number&gt;</footer>\n");

        // ---------------- JS ----------------
        html.append("<script>\n").append(JS).append("\n</script>\n");
        html.append("</body>\n</html>\n");

        Path outFile = runFolder.resolve("dashboard.html");
        Files.writeString(outFile, html.toString(), StandardCharsets.UTF_8);
        return outFile;
    }

    private static String renderJobCard(Job job, Profile profile, int i, Set<String> seenUrls) {
        JobScorer.ScoreResult result = JobScorer.score(job, profile.skills(), profile.targetRole());
        int score = result.score();
        String scoreClass = score >= 70 ? "high" : (score >= 55 ? "mid" : "low");
        boolean isNew = !seenUrls.contains(job.dedupeKey());
        boolean isWalkin = JobScorer.isWalkinJob(job);

        String coverLetter = buildCoverLetter(job, profile, result);
        String pitch = buildPitch(profile, result);
        boolean companyBoard = job.isCompanyBoardUrl();
        String cliCommand = buildCliCommand(job, companyBoard);

        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"card\">\n<div class=\"cardtop\">\n<div>\n");

        sb.append("<div class=\"jobtitle\">#").append(i).append(" ").append(escape(job.title()));
        if (isNew) sb.append(" <span class=\"newbadge\">NEW</span>");
        else sb.append(" <span class=\"seenbadge\">Shown in earlier run</span>");
        if (isWalkin) sb.append(" <span class=\"walkinbadge\">WALK-IN</span>");
        sb.append("</div>\n");

        sb.append("<div class=\"meta\">").append(escape(job.company()))
                .append(" \u00b7 ").append(escape(job.location().isBlank() ? "Location not specified" : job.location()))
                .append(" \u00b7 via ").append(escape(job.source()))
                .append(job.postedDate().isBlank() ? "" : " \u00b7 " + escape(job.postedDate()))
                .append("</div>\n");

        sb.append("<div class=\"tags\">");
        for (String skill : result.matchedSkills()) {
            sb.append("<span class=\"tag\">").append(escape(skill)).append("</span>");
        }
        sb.append("</div>\n");

        sb.append("</div>\n"); // left column
        sb.append("<div class=\"score ").append(scoreClass).append("\">").append(score).append("%</div>\n");
        sb.append("</div>\n"); // .cardtop

        sb.append("<div class=\"actions\">\n");
        sb.append("<a class=\"btn apply\" href=\"").append(escape(job.url()))
                .append("\" target=\"_blank\" rel=\"noopener\">Open &amp; Apply</a>\n");
        sb.append("<button class=\"btn ghost\" onclick=\"toggle('kit").append(i).append("')\">Application kit</button>\n");
        sb.append("<button class=\"btn ghost\" onclick=\"copyCmd('cmd").append(i).append("')\">")
                .append(companyBoard ? "Copy real auto-apply command" : "Copy assisted-fill command")
                .append("</button>\n");
        sb.append("</div>\n");

        sb.append("<textarea id=\"cmd").append(i).append("\" style=\"position:absolute;left:-9999px;\">")
                .append(escape(cliCommand)).append("</textarea>\n");

        sb.append("<div class=\"kit\" id=\"kit").append(i).append("\">\n");

        sb.append("<div class=\"kitlabel\">Short pitch (for \"About you\" boxes) ")
                .append("<button class=\"copy\" onclick=\"copyText('pitch").append(i).append("')\">Copy</button></div>\n");
        sb.append("<pre id=\"pitch").append(i).append("\">").append(escape(pitch)).append("</pre>\n");

        sb.append("<div class=\"kitlabel\">Cover letter ")
                .append("<button class=\"copy\" onclick=\"copyText('cl").append(i).append("')\">Copy</button></div>\n");
        sb.append("<pre id=\"cl").append(i).append("\">").append(escape(coverLetter)).append("</pre>\n");

        sb.append("<div class=\"kitlabel\">Quick answers</div>\n");
        sb.append("<pre>Name: ").append(escape(profile.name()))
                .append("\nEmail: ").append(escape(profile.email()))
                .append("\nPhone: ").append(escape(profile.phone().isBlank() ? "(not on file)" : profile.phone()))
                .append("\nNotice period: Immediate joiner")
                .append("\nExperience: Fresher (B.Tech Computer Science)</pre>\n");

        sb.append("</div>\n"); // .kit
        sb.append("</div>\n"); // .card

        return sb.toString();
    }

    private static String buildCliCommand(Job job, boolean companyBoard) {
        String jar = "ResumeJobHunter-4.0-jar-with-dependencies.jar";
        if (companyBoard) {
            return "java -jar " + jar + " --auto-apply \"" + job.url() + "\" --company \"" + job.company()
                    + "\" --title \"" + job.title() + "\" # add --live once the dry run looks right";
        }
        return "java -jar " + jar + " --assist \"" + job.url() + "\" --company \"" + job.company()
                + "\" --title \"" + job.title() + "\"";
    }

    private static String buildCoverLetter(Job job, Profile profile, JobScorer.ScoreResult result) {
        List<String> matched = result.matchedSkills();
        List<String> topMatched = matched.size() <= 6 ? matched : matched.subList(0, 6);
        String skillsList = topMatched.isEmpty() ? String.join(", ", profile.topSkills(6)) : String.join(", ", topMatched);
        String phoneSuffix = profile.phone().isBlank() ? "" : " | " + profile.phone();

        return """
                Dear Hiring Team at %s,

                I am writing to apply for the %s position. I recently completed my B.Tech in
                Computer Science and have hands-on experience with %s through
                academic projects and self-driven work.

                As an early-career developer, I bring strong fundamentals in data structures and problem
                solving, a fast learning curve, and genuine enthusiasm for building real products. I am
                available to join immediately and am comfortable working remotely or on-site.

                I would welcome the chance to discuss how I can contribute to your team.

                Sincerely,
                %s
                %s%s
                """.formatted(
                nullSafe(job.company()), nullSafe(job.title()), skillsList,
                nullSafe(profile.name()), nullSafe(profile.email()), phoneSuffix
        ).stripTrailing();
    }

    private static String buildPitch(Profile profile, JobScorer.ScoreResult result) {
        List<String> matched = result.matchedSkills();
        List<String> topMatched = matched.size() <= 5 ? matched : matched.subList(0, 5);
        String skillsList = topMatched.isEmpty() ? String.join(", ", profile.topSkills(5)) : String.join(", ", topMatched);
        return """
                B.Tech CS graduate skilled in %s. Strong DSA fundamentals, quick
                learner, immediate joiner, open to remote and on-site roles.
                """.formatted(skillsList).stripTrailing();
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    // ------------------------------------------------------------------
    // Static CSS block
    // ------------------------------------------------------------------
    private static final String CSS = """
            :root {
              --ink:#1b2733; --paper:#f6f8f7; --card:#ffffff; --line:#dde4e1;
              --go:#0e7a4d; --go-soft:#e3f2ea; --mid:#8a6d1a; --mid-soft:#f7efd8;
              --low:#6b7280; --low-soft:#eef0f2; --accent:#0e7a4d;
            }
            * { box-sizing: border-box; }
            body {
              margin:0; background:var(--paper); color:var(--ink);
              font:15px/1.55 "Segoe UI", system-ui, sans-serif;
            }
            header {
              background:#16211d; color:#fff; padding:26px 22px;
            }
            header h1 { margin:0 0 6px 0; font-size:20px; }
            header .sub { color:#c9d6cf; font-size:13px; }
            .wrap { max-width:900px; margin:0 auto; padding:20px 16px 60px; }
            .card {
              background:var(--card); border:1px solid var(--line); border-radius:10px;
              padding:16px; margin-bottom:14px;
            }
            .cardtop { display:flex; justify-content:space-between; gap:12px; }
            .jobtitle { font-weight:700; font-size:16px; margin-bottom:4px; }
            .meta { color:#5a6b78; font-size:13px; margin-bottom:8px; }
            .tags { margin-top:4px; }
            .tag {
              display:inline-block; background:var(--go-soft); color:var(--go);
              border-radius:20px; font-size:12px; padding:2px 10px; margin:0 4px 4px 0;
            }
            .newbadge {
              background:var(--go); color:#fff; font-size:11px; font-weight:700;
              border-radius:5px; padding:2px 7px; margin-left:6px;
            }
            .seenbadge {
              background:var(--low-soft); color:var(--low); font-size:11px; font-weight:700;
              border-radius:5px; padding:2px 7px; margin-left:6px;
            }
            .walkinbadge {
              background:var(--mid-soft); color:var(--mid); font-size:11px; font-weight:700;
              border-radius:5px; padding:2px 7px; margin-left:6px;
            }
            .score {
              width:58px; height:58px; border-radius:50%; display:flex; align-items:center;
              justify-content:center; font-weight:700; font-size:15px; flex-shrink:0;
            }
            .score.high { background:var(--go-soft); color:var(--go); }
            .score.mid  { background:var(--mid-soft); color:var(--mid); }
            .score.low  { background:var(--low-soft); color:var(--low); }
            .btn {
              padding:8px 16px; border-radius:8px; font-weight:600; font-size:14px;
              text-decoration:none; cursor:pointer; display:inline-block; border:none;
            }
            .btn.apply { background:var(--accent); color:#fff; }
            .btn.ghost { background:transparent; color:var(--accent); border:1px solid var(--accent); }
            .btn.linkbtn {
              background:#fff; border:1px solid var(--line); font-size:13px; padding:7px 12px;
              margin:0 8px 8px 0; color:var(--ink);
            }
            .actions { margin-top:12px; display:flex; gap:8px; flex-wrap:wrap; }
            .grouplabel {
              font-weight:700; font-size:13px; color:#5a6b78; text-transform:uppercase;
              letter-spacing:.5px; margin:14px 0 8px;
            }
            .linkrow { margin-bottom:4px; }
            .kit { display:none; border-top:1px dashed var(--line); padding-top:12px; margin-top:12px; }
            .kit.open { display:block; }
            .kitlabel { font-weight:600; font-size:13px; margin:10px 0 4px; }
            pre {
              background:#f2f5f4; border:1px solid var(--line); border-radius:8px;
              padding:10px 12px; white-space:pre-wrap; font:13px/1.5 Consolas, monospace;
            }
            .notice {
              background:#fff8e6; border:1px solid #eadfb0; border-radius:10px;
              padding:12px 14px; font-size:13px; margin-bottom:16px;
            }
            .copy {
              font-size:12px; padding:3px 10px; border-radius:6px; border:1px solid var(--line);
              background:#fff; cursor:pointer;
            }
            footer { text-align:center; color:#8a99a6; font-size:12px; padding:20px 0 40px; }
            @media (max-width:600px) { .cardtop { flex-direction:column; } }
            """;

    // ------------------------------------------------------------------
    // Static JS block
    // ------------------------------------------------------------------
    private static final String JS = """
            function toggle(id){document.getElementById(id).classList.toggle('open');}
            function copyText(id){navigator.clipboard.writeText(document.getElementById(id).innerText).then(()=>alert('Copied!'));}
            function copyCmd(id){navigator.clipboard.writeText(document.getElementById(id).value).then(()=>alert('Command copied! Paste it in your terminal.'));}
            """;
}
