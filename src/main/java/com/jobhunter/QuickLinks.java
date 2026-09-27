package com.jobhunter;

import com.jobhunter.model.Profile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds "Quick Apply" deep links into LinkedIn / Naukri / Shine / Indeed.
 * <p>
 * We never scrape or auto-submit on these platforms (they ban accounts that
 * do). Instead we hand the candidate a pre-filtered search URL they open
 * and apply from themselves.
 */
public final class QuickLinks {

    /** A single clickable link. */
    public record Link(String label, String url) {}

    /** A named group of links, e.g. "By skill". */
    public record Group(String name, List<Link> links) {}

    private static final List<String> CITIES = List.of(
            "Bangalore", "Hyderabad", "Pune", "Noida", "Chennai", "Mumbai", "Gurgaon", "Delhi"
    );

    private static final List<String> ROLES = List.of(
            "software engineer", "software developer", "sde", "web developer",
            "qa engineer", "support engineer", "data analyst"
    );

    private QuickLinks() {
        // static utility
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static String slug(String s) {
        return enc(s.toLowerCase().replace(' ', '-'));
    }

    public static List<Group> build(Profile profile) {
        List<Group> groups = new ArrayList<>();
        List<String> topSkills = profile.topSkills(6);

        groups.add(bySkill(topSkills));
        groups.add(byCity(topSkills));
        groups.add(byRole());
        groups.add(freshest(topSkills));
        groups.add(thisWeekLinkedIn());
        groups.add(walkinDrives(topSkills));
        groups.add(shine(topSkills));
        groups.add(indeed(topSkills));

        return groups;
    }

    // ------------------------------------------------------------------
    private static Group bySkill(List<String> skills) {
        List<Link> links = new ArrayList<>();
        for (String skill : skills) {
            links.add(new Link("Naukri: " + skill + " (fresher)",
                    "https://www.naukri.com/" + slug(skill) + "-fresher-jobs?jobAge=3"));
            links.add(new Link("LinkedIn Easy Apply: " + skill,
                    "https://www.linkedin.com/jobs/search/?keywords=" + enc(skill)
                            + "&f_E=1%2C2&f_AL=true&location=India"));
        }
        return new Group("By skill", links);
    }

    // ------------------------------------------------------------------
    private static Group byCity(List<String> skills) {
        String primarySkill = skills.isEmpty() ? "developer" : skills.get(0);
        List<Link> links = new ArrayList<>();
        for (String city : CITIES) {
            links.add(new Link("Naukri: " + city,
                    "https://www.naukri.com/" + slug(primarySkill) + "-fresher-jobs-in-"
                            + slug(city) + "?jobAge=3"));
        }
        return new Group("By city (Naukri)", links);
    }

    // ------------------------------------------------------------------
    private static Group byRole() {
        List<Link> links = new ArrayList<>();
        for (String role : ROLES) {
            links.add(new Link("Naukri: " + role,
                    "https://www.naukri.com/" + slug(role) + "-fresher-jobs?jobAge=3"));
        }
        links.add(new Link("LinkedIn Easy Apply (India)",
                "https://www.linkedin.com/jobs/search/?keywords=" + enc("software engineer")
                        + "&f_E=1%2C2&f_AL=true&location=India"));
        links.add(new Link("LinkedIn Remote",
                "https://www.linkedin.com/jobs/search/?keywords=" + enc("software engineer")
                        + "&f_E=1%2C2&f_AL=true&f_WT=2"));
        return new Group("By role", links);
    }

    // ------------------------------------------------------------------
    private static Group freshest(List<String> skills) {
        String primarySkill = skills.isEmpty() ? "developer" : skills.get(0);
        List<Link> links = new ArrayList<>();
        links.add(new Link("Naukri: IT fresher jobs (last 3 days)",
                "https://www.naukri.com/it-fresher-jobs?jobAge=3"));
        links.add(new Link("Naukri: " + primarySkill + " (last 3 days)",
                "https://www.naukri.com/" + slug(primarySkill) + "-fresher-jobs?jobAge=3"));
        links.add(new Link("LinkedIn Easy Apply (last 3 days)",
                "https://www.linkedin.com/jobs/search/?keywords=" + enc("software engineer")
                        + "&f_E=1%2C2&f_AL=true&location=India&f_TPR=r259200"));
        return new Group("Freshest (posted in last 3 days)", links);
    }

    // ------------------------------------------------------------------
    private static Group thisWeekLinkedIn() {
        List<Link> links = new ArrayList<>();
        for (String kw : List.of("Java Developer", "Software Engineer", "SDE")) {
            links.add(new Link(kw + " · India (past week)",
                    "https://www.linkedin.com/jobs/search/?keywords=" + enc(kw)
                            + "&location=India&f_TPR=r604800"));
            links.add(new Link(kw + " · Remote (past week)",
                    "https://www.linkedin.com/jobs/search/?keywords=" + enc(kw)
                            + "&f_WT=2&f_TPR=r604800"));
        }
        return new Group("LinkedIn \u00b7 This week's postings (Java / SE / SDE)", links);
    }

    // ------------------------------------------------------------------
    private static Group walkinDrives(List<String> skills) {
        List<Link> links = new ArrayList<>();
        List<String> roles = List.of("software engineer", "java developer", "web developer", "sde");
        int count = 0;
        for (String role : roles) {
            if (count++ >= 4) break;
            String kw = "walk-in drive " + role;
            links.add(new Link("LinkedIn posts: " + kw,
                    "https://www.linkedin.com/search/results/content/?keywords=" + enc(kw)
                            + "&sortBy=%22date_posted%22&datePosted=%22past-week%22"));
        }
        for (String city : CITIES) {
            links.add(new Link("Naukri walk-in: " + city,
                    "https://www.naukri.com/walkin-jobs-in-" + slug(city)));
        }
        links.add(new Link("Naukri: all walk-in jobs", "https://www.naukri.com/walkin-jobs"));
        return new Group("Walk-in drives", links);
    }

    // ------------------------------------------------------------------
    private static Group shine(List<String> skills) {
        List<Link> links = new ArrayList<>();
        for (String skill : skills) {
            links.add(new Link("Shine: " + skill + " (fresher)",
                    "https://www.shine.com/job-search/" + slug(skill) + "-fresher-jobs"));
        }
        return new Group("Shine", links);
    }

    // ------------------------------------------------------------------
    private static Group indeed(List<String> skills) {
        List<Link> links = new ArrayList<>();
        for (String skill : skills) {
            links.add(new Link("Indeed: " + skill + " (fresher, last 7 days)",
                    "https://in.indeed.com/jobs?q=" + enc(skill + " fresher") + "&l=India&fromage=7"));
        }
        return new Group("Indeed", links);
    }
}
