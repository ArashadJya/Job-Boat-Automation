package com.jobhunter;

import com.jobhunter.model.Profile;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads a resume (PDF or DOCX) and extracts a {@link Profile}: name, email,
 * phone, matched skills, and a best-guess target role.
 *
 * No external NLP — everything is done with plain-text heuristics + regex,
 * which is more than enough for structured resume text.
 */
public final class ResumeParser {

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");

    private static final Pattern PHONE_PATTERN =
            Pattern.compile("(\\+?91[\\s-]?)?[6-9]\\d{4}[\\s-]?\\d{5}");

    /** Role phrases checked in this order; first match wins. */
    private static final List<String> ROLE_HINT_WORDS = List.of(
            "full stack developer", "full stack engineer", "backend developer",
            "backend engineer", "frontend developer", "frontend engineer",
            "software development engineer", "software engineer", "software developer",
            "java developer", "python developer", "web developer", "mobile developer",
            "android developer", "ios developer", "data scientist", "data analyst",
            "data engineer", "machine learning engineer", "devops engineer",
            "qa engineer", "test engineer", "cloud engineer", "system engineer"
    );

    /** Canonicalization map: near-duplicate skill spellings -> canonical form. */
    private static final Map<String, String> CANONICAL = Map.ofEntries(
            Map.entry("reactjs", "react"),
            Map.entry("react.js", "react"),
            Map.entry("nodejs", "node"),
            Map.entry("node.js", "node"),
            Map.entry("nextjs", "next.js"),
            Map.entry("vuejs", "vue"),
            Map.entry("vue.js", "vue"),
            Map.entry("springboot", "spring boot"),
            Map.entry("postgres", "postgresql"),
            Map.entry("sklearn", "scikit-learn"),
            Map.entry("golang", "go"),
            Map.entry("oop", "oops"),
            Map.entry("object oriented programming", "oops")
    );

    private ResumeParser() {
        // static utility
    }

    /**
     * Parses a resume file and builds a {@link Profile}.
     *
     * @param file path to a .pdf or .docx resume
     * @return extracted profile (never null; fields default to "" or empty list)
     */
    public static Profile parse(Path file) {
        String rawText;
        try {
            String lower = file.getFileName().toString().toLowerCase();
            if (lower.endsWith(".pdf")) {
                rawText = extractPdfText(file);
            } else if (lower.endsWith(".docx")) {
                rawText = extractDocxText(file);
            } else {
                // Fall back to reading as plain text (e.g. .txt resumes).
                rawText = Files.readString(file, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            System.err.println("  ! Failed to read " + file.getFileName() + ": " + e.getMessage());
            rawText = "";
        }

        String name = extractName(rawText, file);
        String email = firstMatch(EMAIL_PATTERN, rawText);
        String phone = firstMatch(PHONE_PATTERN, rawText);
        List<String> skills = extractSkills(rawText);
        String targetRole = extractTargetRole(rawText);

        Profile profile = new Profile(file.getFileName().toString(), name, email, phone, skills, targetRole);

        System.out.println("  \u2713 " + profile.file() + ": " + profile.name()
                + " | " + skills.size() + " skills -> "
                + String.join(", ", profile.topSkills(8))
                + (skills.size() > 8 ? ", ..." : "")
                + " | target role: " + (targetRole.isBlank() ? "(not detected)" : targetRole));

        return profile;
    }

    // ------------------------------------------------------------------
    // PDF extraction (Apache PDFBox)
    // ------------------------------------------------------------------
    private static String extractPdfText(Path file) throws IOException {
        try (PDDocument doc = Loader.loadPDF(file.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc);
        }
    }

    // ------------------------------------------------------------------
    // DOCX extraction (plain ZipInputStream + regex, no poi-ooxml)
    // ------------------------------------------------------------------
    private static String extractDocxText(Path file) throws IOException {
        StringBuilder xml = new StringBuilder();
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(file), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if ("word/document.xml".equals(entry.getName())) {
                    xml.append(readAll(zis));
                    break;
                }
            }
        }
        String content = xml.toString();
        // Turn paragraph ends into newlines before stripping tags, so words don't run together.
        content = content.replace("</w:p>", "\n");
        content = content.replaceAll("<[^>]+>", " ");
        // Collapse excess whitespace but keep the newlines we just inserted.
        content = content.replaceAll("[ \\t]+", " ");
        return content;
    }

    private static String readAll(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Field extraction
    // ------------------------------------------------------------------

    private static String firstMatch(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group().trim() : "";
    }

    /**
     * Name heuristic: first non-empty line that isn't the email/phone line,
     * has no digits, is at most 5 words, and under 40 characters.
     * Falls back to a title-cased version of the file name.
     */
    private static String extractName(String text, Path file) {
        for (String rawLine : text.split("\\R")) {
            String line = rawLine.strip();
            if (line.isEmpty()) continue;
            if (line.length() >= 40) continue;
            if (line.chars().anyMatch(Character::isDigit)) continue;
            if (line.contains("@")) continue;
            String[] words = line.split("\\s+");
            if (words.length == 0 || words.length > 5) continue;
            return line;
        }
        return titleCaseFileName(file);
    }

    private static String titleCaseFileName(Path file) {
        String base = file.getFileName().toString().replaceAll("\\.[a-zA-Z0-9]+$", "");
        base = base.replaceAll("[_\\-.]+", " ").strip();
        if (base.isBlank()) return "Candidate";
        StringBuilder sb = new StringBuilder();
        for (String w : base.split("\\s+")) {
            if (w.isEmpty()) continue;
            sb.append(Character.toUpperCase(w.charAt(0)))
                    .append(w.length() > 1 ? w.substring(1).toLowerCase() : "")
                    .append(' ');
        }
        return sb.toString().strip();
    }

    private static List<String> extractSkills(String text) {
        String lower = text.toLowerCase();
        List<String> known = loadKnownSkills();
        // Preserve first-seen order, no duplicates, canonicalized.
        Set<String> matched = new LinkedHashSet<>();
        for (String skill : known) {
            Pattern p = Pattern.compile("(?<![a-z0-9])" + Pattern.quote(skill) + "(?![a-z0-9])");
            if (p.matcher(lower).find()) {
                matched.add(CANONICAL.getOrDefault(skill, skill));
            }
        }
        return new ArrayList<>(matched);
    }

    private static String extractTargetRole(String text) {
        String lower = text.toLowerCase();
        for (String role : ROLE_HINT_WORDS) {
            if (lower.contains(role)) {
                return titleCase(role);
            }
        }
        return "";
    }

    private static String titleCase(String s) {
        StringBuilder sb = new StringBuilder();
        for (String w : s.split("\\s+")) {
            if (w.isEmpty()) continue;
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
        }
        return sb.toString().strip();
    }

    // ------------------------------------------------------------------
    // skills.txt loader (external config, ~150 entries)
    // ------------------------------------------------------------------
    private static List<String> knownSkillsCache;

    private static synchronized List<String> loadKnownSkills() {
        if (knownSkillsCache != null) return knownSkillsCache;
        List<String> list = new ArrayList<>();
        try (InputStream in = ResumeParser.class.getResourceAsStream("/skills.txt")) {
            if (in == null) {
                System.err.println("  ! skills.txt not found on classpath; skill matching disabled.");
                knownSkillsCache = list;
                return list;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String s = line.strip().toLowerCase();
                    if (s.isEmpty() || s.startsWith("#")) continue;
                    list.add(s);
                }
            }
        } catch (IOException e) {
            System.err.println("  ! Failed to read skills.txt: " + e.getMessage());
        }
        // Longer phrases first so "full stack developer" matches before "developer"-ish substrings interfere.
        list.sort((a, b) -> Integer.compare(b.length(), a.length()));
        knownSkillsCache = list;
        return list;
    }
}
